package pl.home.channeldeck

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Many broadcast streams carry MPEG-1 Layer II (MP2) audio, which Android TV devices usually have no decoder for
 * (the video then plays silently). This renderer decodes it in software with JLayer and is added after the normal
 * hardware renderer, so it only takes over for MP2.
 */
class Mp2Exception(msg: String, cause: Throwable? = null) : DecoderException(msg, cause)

class Mp2Decoder(private val fallbackChannels: Int, private val fallbackRate: Int) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, Mp2Exception>(
        arrayOfNulls<DecoderInputBuffer>(4) as Array<DecoderInputBuffer>,
        arrayOfNulls<SimpleDecoderOutputBuffer>(4) as Array<SimpleDecoderOutputBuffer>) {

    /** JLayer binds its layer decoder to the first Bitstream it sees, so one Bitstream must live as long as the Decoder. */
    private class Feed : java.io.InputStream() {
        private var buf = ByteArray(0); private var p = 0
        fun feed(b: ByteArray) { val rem = buf.size - p; val n = ByteArray(rem + b.size); System.arraycopy(buf, p, n, 0, rem); System.arraycopy(b, 0, n, rem, b.size); buf = n; p = 0 }
        override fun read(): Int = if (p < buf.size) buf[p++].toInt() and 0xFF else -1
        override fun read(o: ByteArray, off: Int, len: Int): Int { val n = minOf(len, buf.size - p); if (n <= 0) return -1; System.arraycopy(buf, p, o, off, n); p += n; return n }
        override fun available() = buf.size - p
    }
    private var feed = Feed()
    private var bs = Bitstream(feed)
    private var dec = Decoder()
    private fun resetState() { feed = Feed(); bs = Bitstream(feed); dec = Decoder() }
    @Volatile var channels = fallbackChannels
    @Volatile var rate = fallbackRate

    init { setInitialInputBufferSize(16 * 1024) }

    override fun getName() = "JLayerMp2"
    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    override fun createOutputBuffer() = SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }
    override fun createUnexpectedDecodeException(error: Throwable) = Mp2Exception("MP2 decode failed", error)

    override fun decode(input: DecoderInputBuffer, output: SimpleDecoderOutputBuffer, reset: Boolean): Mp2Exception? {
        if (reset) resetState()
        val data = input.data ?: return null
        val bytes = ByteArray(data.remaining()); data.get(bytes)
        val pcm = ByteArrayOutputStream(4608)
        feed.feed(bytes)
        try {
            while (feed.available() > 0) {
                val h = bs.readFrame() ?: break
                val sb = dec.decodeFrame(h, bs) as SampleBuffer
                channels = sb.channelCount; rate = sb.sampleFrequency
                val s = sb.buffer
                for (i in 0 until sb.bufferLength) { val v = s[i].toInt(); pcm.write(v and 0xFF); pcm.write((v shr 8) and 0xFF) }
                bs.closeFrame()
            }
        } catch (e: Throwable) {
            resetState()    // damaged data: keep what was decoded and resync cleanly on the next sample
        }
        if (pcm.size() == 0) { output.shouldBeSkipped = true; return null }
        val out = output.init(input.timeUs, pcm.size())
        out.put(pcm.toByteArray()); out.position(0)
        return null
    }
}

class Mp2AudioRenderer(handler: Handler?, listener: AudioRendererEventListener?, sink: AudioSink) :
    DecoderAudioRenderer<Mp2Decoder>(handler, listener, sink) {

    override fun getName() = "Mp2AudioRenderer"

    private fun pcm(ch: Int, rate: Int) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setChannelCount(ch).setSampleRate(rate).setPcmEncoding(C.ENCODING_PCM_16BIT).build()

    override fun supportsFormatInternal(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.AUDIO_MPEG_L2) return C.FORMAT_UNSUPPORTED_TYPE
        val ch = if (format.channelCount > 0) format.channelCount else 2
        val rate = if (format.sampleRate > 0) format.sampleRate else 48000
        return if (sinkSupportsFormat(pcm(ch, rate))) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?) =
        Mp2Decoder(if (format.channelCount > 0) format.channelCount else 2, if (format.sampleRate > 0) format.sampleRate else 48000)

    override fun getOutputFormat(decoder: Mp2Decoder): Format = pcm(decoder.channels, decoder.rate)
}

class Mp2RenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        audioSink: AudioSink, eventHandler: Handler, eventListener: AudioRendererEventListener, out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        out.add(Mp2AudioRenderer(eventHandler, eventListener, audioSink))
    }
}
