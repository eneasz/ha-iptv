/* HA IPTV launcher card: muted live preview of the last channel + "Watch" button that opens /iptv-player. */
const LS = "ha_iptv_player";
class HaIptvCard extends HTMLElement {
  setConfig(c) { this._cfg = { preview: true, ...c }; if (!this.shadowRoot) this.build(); }
  getCardSize() { return 4; }
  static getStubConfig() { return { preview: true }; }
  build() {
    this.attachShadow({ mode: "open" }).innerHTML = `<style>
      ha-card{overflow:hidden;position:relative;background:#000;color:#fff}
      .box{position:relative;aspect-ratio:16/9;background:linear-gradient(135deg,#0a1440,#050a24)}
      video{width:100%;height:100%;object-fit:cover;display:block;opacity:0;transition:opacity .4s}
      video.on{opacity:1}
      .bar{position:absolute;left:0;right:0;bottom:0;display:flex;align-items:center;gap:12px;padding:14px 16px;background:linear-gradient(transparent,rgba(5,10,36,.92))}
      .t{flex:1;min-width:0}.t b{display:block;font-size:17px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.t small{opacity:.75}
      button{font:inherit;font-weight:600;border:0;border-radius:999px;padding:12px 22px;min-height:44px;background:#f4ff00;color:#050a24;cursor:pointer}
      button:focus-visible{outline:3px solid #fff;outline-offset:2px}
    </style><ha-card><div class="box"><video muted playsinline></video>
      <div class="bar"><div class="t"><b id="n">HA IPTV</b><small id="s"></small></div><button id="w" aria-label="Open full-screen player">Watch</button></div></div></ha-card>`;
    this.shadowRoot.getElementById("w").onclick = () => { history.pushState(null, "", "/iptv-player"); window.dispatchEvent(new CustomEvent("location-changed")); };
    this.v = this.shadowRoot.querySelector("video");
    new IntersectionObserver((e) => { this.visible = e[0].isIntersecting; this.visible ? this.preview() : this.stop(); }).observe(this);
  }
  set hass(h) { this._hass = h; if (!this._seen) { this._seen = true; this.refresh(); } }
  saved() { try { return JSON.parse(localStorage.getItem(LS) || "{}"); } catch (e) { return {}; } }
  refresh() {
    const s = this.saved();
    this.shadowRoot.getElementById("n").textContent = s.name || "HA IPTV";
    this.shadowRoot.getElementById("s").textContent = s.name ? "Last watched" : "Open the player to pick a channel";
  }
  async preview() {
    const s = this.saved();
    if (!this._cfg.preview || !s.id || this.hls || !this._hass) return;
    try {
      if (!window.Hls) await new Promise((ok, no) => { const e = document.createElement("script"); e.src = "/ha_iptv_static/hls.min.js"; e.onload = ok; e.onerror = no; document.head.appendChild(e); });
      const a = this._hass.auth; if (a.expired) await a.refreshAccessToken();
      if (!Hls.isSupported()) return;
      const hls = (this.hls = new Hls({ maxBufferLength: 6, xhrSetup: (x) => x.setRequestHeader("Authorization", "Bearer " + this._hass.auth.data.access_token) }));
      hls.on(Hls.Events.MANIFEST_PARSED, () => this.v.play().then(() => this.v.classList.add("on")).catch(() => {}));
      hls.loadSource(`/api/ha_iptv/stream/${s.id}.m3u8`); hls.attachMedia(this.v);
    } catch (e) {}
  }
  stop() { if (this.hls) { this.hls.destroy(); this.hls = null; } this.v.pause(); this.v.classList.remove("on"); }
  disconnectedCallback() { this.stop(); }
}
customElements.define("ha-iptv-card", HaIptvCard);
window.customCards = window.customCards || [];
window.customCards.push({ type: "ha-iptv-card", name: "HA IPTV", description: "Live preview and launcher for the full-screen IPTV player" });
