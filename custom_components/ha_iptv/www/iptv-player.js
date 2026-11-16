/* HA IPTV full-screen player panel (/iptv-player). Plays live channels through the ha_iptv proxy views. */
const API = "/api/ha_iptv";
const LS = "ha_iptv_player";
const css = `
:host{display:block;height:100%;background:#000;color:#eef2ff;font-family:Roboto,system-ui,sans-serif;--glass:rgba(12,18,48,.78);--ring:#f4ff00;--blue:#2a6bff}
*{box-sizing:border-box}
.stage{position:relative;width:100%;height:100%;background:#000;overflow:hidden;cursor:default}
.stage.idle{cursor:none}
video{width:100%;height:100%;object-fit:contain;background:#000;display:block}
.toast{position:absolute;left:24px;top:24px;max-width:70%;padding:12px 18px;border-radius:14px;background:var(--glass);backdrop-filter:blur(14px);font-size:20px;opacity:0;transition:opacity .25s;pointer-events:none}
.toast.on{opacity:1}
.toast small{display:block;font-size:13px;opacity:.75;margin-top:2px}
.state{position:absolute;inset:0;display:none;align-items:center;justify-content:center;flex-direction:column;gap:14px;font-size:20px;pointer-events:none;text-align:center;padding:24px}
.state.on{display:flex}
.spin{width:44px;height:44px;border-radius:50%;border:4px solid rgba(255,255,255,.2);border-top-color:var(--ring);animation:sp 1s linear infinite}
@keyframes sp{to{transform:rotate(360deg)}}
button{font:inherit;color:inherit;border:0;cursor:pointer}
button:focus-visible,.row:focus-visible,input:focus-visible{outline:3px solid var(--ring);outline-offset:2px}
.hot{position:absolute;right:0;bottom:0;width:140px;height:140px}
.fab{display:flex;align-items:center;justify-content:center;position:absolute;right:16px;bottom:16px;width:48px;height:48px;border-radius:50%;background:rgba(12,18,48,.88);border:1px solid rgba(255,255,255,.28);box-shadow:0 4px 16px rgba(0,0,0,.5);backdrop-filter:blur(14px);font-size:22px;opacity:0;transition:opacity .4s}
.fab.pinned{border-color:var(--blue);box-shadow:0 0 0 2px var(--blue),0 4px 16px rgba(0,0,0,.5)}
.tip{position:absolute;right:72px;bottom:26px;padding:6px 12px;border-radius:10px;background:var(--glass);font-size:13px;opacity:0;transition:opacity .3s;pointer-events:none;white-space:nowrap}
.tip.on{opacity:1}
.stage.ui .fab,.hot:hover .fab,.fab:focus-visible{opacity:1;transition-duration:.2s}
.remote{position:absolute;right:16px;bottom:76px;width:248px;padding:14px;border-radius:22px;background:rgba(12,18,48,.86);border:1px solid rgba(255,255,255,.14);box-shadow:0 10px 30px rgba(0,0,0,.5);backdrop-filter:blur(16px);display:grid;grid-template-columns:repeat(3,1fr);gap:8px;opacity:0;visibility:hidden;transition:opacity .4s,visibility .4s}
.remote.on{opacity:1;visibility:visible;transition-duration:.2s}
.remote button{display:flex;align-items:center;justify-content:center;min-height:48px;border-radius:14px;background:rgba(255,255,255,.1);font-size:18px}
.remote button:hover{background:rgba(255,255,255,.22)}
.remote .ok{background:var(--blue);font-weight:600}
.remote .cap{grid-column:1/-1;font-size:11px;letter-spacing:.06em;text-transform:uppercase;opacity:.75;text-align:center;margin:-2px 0}
.remote button.on{background:rgba(255,200,60,.35)}
.menu{position:absolute;inset:0;background:rgba(5,10,36,.78);backdrop-filter:blur(14px);display:none;padding:32px 40px;gap:24px}
.menu.on{display:flex}
.stage.menu-on .remote{display:none}
.col{display:flex;flex-direction:column;min-width:0}
.col.g{flex:0 0 32%}.col.c{flex:1}
.col h2{margin:0 0 10px;font-size:15px;font-weight:500;letter-spacing:.08em;text-transform:uppercase;opacity:.7}
.col input::placeholder{color:rgba(238,242,255,.62)}
.col input{padding:12px 14px;border-radius:12px;border:2px solid #3552b8;background:#0f1a44;color:inherit;font-size:17px;margin-bottom:10px}
.list{overflow:auto;flex:1;padding:4px}
.row{display:flex;align-items:center;gap:12px;width:100%;padding:11px 14px;border-radius:12px;background:transparent;text-align:left;font-size:19px;min-height:48px}
.row:hover{background:#172863}
.row.sel{background:#1a2a66;box-shadow:inset 4px 0 0 var(--blue)}
.row img{width:34px;height:34px;object-fit:contain;border-radius:6px;background:#fff}
.row .n{opacity:.6;font-size:15px;min-width:42px}
.row .t{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.row .k{opacity:.6;font-size:15px}
.empty{opacity:.7;padding:24px 16px;font-size:17px}
.hint{opacity:.7;font-size:14px;padding:10px 4px 0}
@media (max-width:760px){.menu{flex-direction:column;padding:16px}.col.g{flex:0 0 38%}}
@media (prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}
`;
const tpl = `
<div class="stage" id="stage" tabindex="-1">
  <video id="v" playsinline></video>
  <div class="toast" id="toast" role="status" aria-live="polite"></div><div class="tip" id="tip">Remote · hover here · F full screen · G channels</div>
  <div class="state" id="state"></div>
  <div class="menu" id="menu">
    <div class="col g"><h2>Groups</h2><input id="gq" placeholder="Filter groups (type to search)" aria-label="Filter groups"><div class="list" id="groups"></div></div>
    <div class="col c"><h2 id="ch">Channels</h2><div class="list" id="chans"></div><div class="hint">Pick a channel to start. ▲▼ browse, Enter plays, Backspace closes.</div></div>
  </div>
  <div class="hot" id="hot">
    <button class="fab" id="fab" aria-label="Remote control" aria-expanded="false"><svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor" aria-hidden="true"><path d="M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zm4 3a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3zM9 11h6v2H9zm0 4h6v2H9z"/></svg></button>
    <div class="remote" id="remote" role="group" aria-label="Remote control">
      <button data-k="menu" aria-label="Channel menu (G)" title="Channels (G)"><svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor" aria-hidden="true"><path d="M3 6h18v2H3zm0 5h18v2H3zm0 5h18v2H3z"/></svg></button><button data-k="up" aria-label="Channel up" title="Channel up">▲</button><button data-k="full" aria-label="Full screen (F)"><svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor" aria-hidden="true"><path d="M5 5h5v2H7v3H5zm9 0h5v5h-2V7h-3zM5 14h2v3h3v2H5zm12 0h2v5h-5v-2h3z"/></svg></button>
      <button data-k="left" aria-label="Left">◀</button><button class="ok" data-k="ok" aria-label="OK">OK</button><button data-k="right" aria-label="Right">▶</button>
      <button data-k="back" aria-label="Back"><svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor" aria-hidden="true"><path d="M20 11H7.8l4.6-4.6L11 5l-7 7 7 7 1.4-1.4L7.8 13H20z"/></svg></button><button data-k="down" aria-label="Channel down" title="Channel down">▼</button><button data-k="mute" id="bmute" aria-label="Mute" title="Mute (M)"><svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor" aria-hidden="true"><path d="M3 9v6h4l5 5V4L7 9zm13.5 3a4.5 4.5 0 0 0-2.5-4v8a4.5 4.5 0 0 0 2.5-4z"/></svg></button>
      <button data-k="play" id="bplay" aria-label="Pause" title="Play / pause (Space)"><svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor" aria-hidden="true"><path d="M4 5v14l8-7zm10 0h3v14h-3zm5 0h-1v14h1z"/></svg></button><button data-k="vdn" aria-label="Volume down" title="Volume down">−</button><button data-k="vup" aria-label="Volume up" title="Volume up">+</button>
    </div>
  </div>
</div>`;

class HaIptvPlayer extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" }).innerHTML = `<style>${css}</style>${tpl}`;
    const $ = (id) => this.shadowRoot.getElementById(id);
    this.$ = $; this.v = $("v"); this.stage = $("stage");
    this.groups = []; this.chans = []; this.group = null; this.cur = null; this.menuOn = false; this.pinned = false;
    this.backAt = 0; this.sel = { col: "c", i: 0 };
    try { Object.assign(this, { saved: JSON.parse(localStorage.getItem(LS) || "{}") }); } catch (e) { this.saved = {}; }
  }
  set hass(h) { this._hass = h; if (!this._init) { this._init = true; this.start(); } }
  set narrow(n) { this._narrow = n; }
  set panel(p) { this._panel = p; }

  // ---------------------------------------------------------------- auth + data
  async token() {
    const a = this._hass.auth;
    if (a.expired) await a.refreshAccessToken();
    return a.data.access_token;
  }
  async json(path) {
    const r = await fetch(API + path, { headers: { Authorization: "Bearer " + (await this.token()) } });
    if (!r.ok) throw new Error(r.status);
    return r.json();
  }
  async loadHls() {
    if (window.Hls) return;
    await new Promise((ok, no) => { const s = document.createElement("script"); s.src = "/ha_iptv_static/hls.min.js"; s.onload = ok; s.onerror = no; document.head.appendChild(s); });
  }

  // ---------------------------------------------------------------- startup
  async start() {
    this.bind();
    this.setState("loading", "Loading channels…");
    try {
      await this.loadHls();
      const d = await this.json("/groups");
      this.groups = (d.groups || []).slice().sort((a, b) => a.name.localeCompare(b.name));
    } catch (e) { this.setState("error", "Can't reach the HA IPTV integration (" + e.message + ")."); return; }
    this.renderGroups(); this.renderChannels();
    const s = this.saved;
    if (s.group && s.id) {
      this.group = s.group; await this.loadChannels(s.group);
      const c = this.chans.find((x) => x.id == s.id);
      if (c) { this.play(c); return; }
    }
    this.setState(null); this.openMenu();
  }

  // ---------------------------------------------------------------- playback
  async play(c) {
    this.cur = c; this.setState("loading", "Tuning " + c.name + "…");
    this.toast(c.name, this.groupName());
    this.saved = { ...this.saved, group: this.group, id: c.id, name: c.name }; try { localStorage.setItem(LS, JSON.stringify(this.saved)); } catch (e) {}
    if (this.hls) { this.hls.destroy(); this.hls = null; }
    const url = `${API}/stream/${c.id}.m3u8`;
    if (!window.Hls || !Hls.isSupported()) { this.setState("error", "This browser can't play live HLS."); return; }
    const hls = (this.hls = new Hls({ lowLatencyMode: false, xhrSetup: (x) => x.setRequestHeader("Authorization", "Bearer " + this._hass.auth.data.access_token) }));
    hls.on(Hls.Events.ERROR, (_e, d) => {
      if (!d.fatal) return;
      if (d.type === Hls.ErrorTypes.NETWORK_ERROR && (this.retry = (this.retry || 0) + 1) <= 3) { setTimeout(() => hls.startLoad(), 1500); return; }
      this.setState("error", `${c.name} isn't playing. Try another channel (▲ ▼).`);
    });
    hls.on(Hls.Events.MANIFEST_PARSED, () => { this.retry = 0; this.v.play().catch(() => this.setState("error", "Press play (Space) to start.")); });
    await this.token();
    hls.loadSource(url); hls.attachMedia(this.v);
  }
  step(d) {
    if (!this.chans.length) return this.openMenu();
    const i = Math.max(0, this.chans.findIndex((x) => this.cur && x.id == this.cur.id));
    this.play(this.chans[(i + d + this.chans.length) % this.chans.length]);
  }
  groupName() { const g = this.groups.find((x) => x.id == this.group); return g ? g.name : ""; }

  // ---------------------------------------------------------------- UI helpers
  setState(kind, msg) {
    const s = this.$("state");
    if (!kind) { s.classList.remove("on"); return; }
    s.innerHTML = (kind === "loading" ? '<div class="spin"></div>' : "") + `<div>${msg}</div>`;
    s.classList.add("on");
  }
  toast(t, sub) {
    const el = this.$("toast"); el.innerHTML = ""; el.append(document.createTextNode(t));
    if (sub) { const sm = document.createElement("small"); sm.textContent = sub; el.append(sm); }
    el.classList.add("on"); clearTimeout(this._tt); this._tt = setTimeout(() => el.classList.remove("on"), 3000);
  }
  poke() { // pointer/keys wake the UI; it hides again after 3 s
    this.stage.classList.add("ui"); this.stage.classList.remove("idle");
    clearTimeout(this._ui);
    this._ui = setTimeout(() => { if (!this.pinned) { this.stage.classList.remove("ui"); this.$("remote").classList.remove("on"); this.$("fab").setAttribute("aria-expanded", "false"); } if (!this.menuOn) this.stage.classList.add("idle"); }, 3000);
  }
  sync() {
    const m = this.$("bmute"), p = this.$("bplay");
    m.classList.toggle("on", this.v.muted); m.setAttribute("aria-label", this.v.muted ? "Unmute" : "Mute");
    p.setAttribute("aria-label", this.v.paused ? "Play" : "Pause");
  }
  showRemote(on) { this.$("remote").classList.toggle("on", on); this.$("fab").setAttribute("aria-expanded", String(on)); }

  // ---------------------------------------------------------------- menu
  async loadChannels(gid) {
    this.$("chans").innerHTML = '<div class="empty">Loading…</div>';
    try { this.chans = await this.json("/channels/" + encodeURIComponent(gid)); } catch (e) { this.chans = []; this.$("chans").innerHTML = '<div class="empty">Could not load channels.</div>'; return; }
    this.renderChannels();
  }
  row(html, cls, fn) { const b = document.createElement("button"); b.className = "row " + (cls || ""); b.innerHTML = html; b.onclick = fn; return b; }
  esc(s) { const d = document.createElement("div"); d.textContent = s == null ? "" : s; return d.innerHTML; }
  renderGroups() {
    const q = this.$("gq").value.trim().toLowerCase(), box = this.$("groups"); box.innerHTML = "";
    const list = this.groups.filter((g) => !q || g.name.toLowerCase().includes(q));
    if (!list.length) box.innerHTML = '<div class="empty">No group matches.</div>';
    list.forEach((g) => box.append(this.row(`<span class="t">${this.esc(g.name)}</span><span class="k">${g.count}</span>`, g.id == this.group ? "sel" : "", async () => { this.group = g.id; this.renderGroups(); await this.loadChannels(g.id); this.focusCol("c"); })));
  }
  renderChannels() {
    const box = this.$("chans"); box.innerHTML = ""; this.$("ch").textContent = this.groupName() || "Channels";
    if (!this.chans.length) box.innerHTML = '<div class="empty">Select a group on the left to see its channels.</div>';
    this.chans.forEach((c) => box.append(this.row(`${c.icon ? `<img loading="lazy" src="${this.esc(c.icon)}" alt="">` : ""}<span class="n">${this.esc(c.num)}</span><span class="t">${this.esc(c.name)}</span>`, this.cur && this.cur.id == c.id ? "sel" : "", () => { this.closeMenu(); this.play(c); })));
  }
  openMenu() { this.menuOn = true; this.stage.classList.add("menu-on"); this.$("menu").classList.add("on"); this.stage.classList.remove("idle"); this.focusCol(this.chans.length ? "c" : "g"); }
  closeMenu() { this.menuOn = false; this.stage.classList.remove("menu-on"); this.$("menu").classList.remove("on"); this.stage.focus(); this.poke(); }
  focusCol(col) {
    const rows = [...this.$(col === "g" ? "groups" : "chans").querySelectorAll(".row")];
    const t = rows.find((r) => r.classList.contains("sel")) || rows[0];
    if (t) { t.focus({ preventScroll: false }); t.scrollIntoView({ block: "nearest" }); }
  }

  // ---------------------------------------------------------------- keys / remote
  press(k) {
    const v = this.v;
    if (this.menuOn) return this.menuKey(k);
    switch (k) {
      case "up": return this.step(1);
      case "down": return this.step(-1);
      case "left": case "vdn": v.volume = Math.max(0, v.volume - 0.1); v.muted = false; return this.toast("Volume " + Math.round(v.volume * 100) + "%");
      case "right": case "vup": v.volume = Math.min(1, v.volume + 0.1); v.muted = false; return this.toast("Volume " + Math.round(v.volume * 100) + "%");
      case "mute": v.muted = !v.muted; return this.toast(v.muted ? "Muted" : "Sound on");
      case "play": return v.paused ? v.play() : v.pause();
      case "ok": case "menu": return this.openMenu();
      case "full": return this.fullscreen();
      case "back": {
        const now = Date.now();
        if (now - this.backAt < 2000) { history.back(); return; }
        this.backAt = now; return this.toast("Press Back again to exit");
      }
    }
  }
  menuKey(k) {
    const a = this.shadowRoot.activeElement;
    const inGroups = a && a.closest && a.closest("#groups"), inChans = a && a.closest && a.closest("#chans");
    const rows = (inGroups ? this.$("groups") : this.$("chans")).querySelectorAll(".row");
    const i = [...rows].indexOf(a);
    if (k === "back" || k === "menu") return this.closeMenu();
    if (k === "full") return this.fullscreen();
    if (k === "up" || k === "down") { const n = rows[Math.max(0, Math.min(rows.length - 1, i + (k === "up" ? -1 : 1)))]; if (n) { n.focus(); n.scrollIntoView({ block: "nearest" }); } return; }
    if (k === "left") return this.focusCol("g");
    if (k === "right") return this.focusCol("c");
    if (k === "ok" && a && a.click) a.click();
  }
  fullscreen() {
    if (document.fullscreenElement) document.exitFullscreen();
    else this.stage.requestFullscreen && this.stage.requestFullscreen().catch(() => {});
  }
  bind() {
    const keymap = { ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right", Enter: "ok", Backspace: "back", Escape: "back", " ": "play", "+": "vup", "=": "vup", "-": "vdn", "]": "up", "[": "down", m: "mute", M: "mute", f: "full", F: "full", g: "menu", G: "menu" };
    this._kd = (e) => {
      if (!this.isConnected || e.ctrlKey || e.metaKey || e.altKey) return;
      const inq = this.shadowRoot.activeElement && this.shadowRoot.activeElement.id === "gq";
      if (inq && e.key !== "ArrowDown" && e.key !== "Escape" && e.key !== "Enter") return; // typing in the filter
      const k = keymap[e.key];
      if (!k) { if (this.menuOn && e.key.length === 1) this.$("gq").focus(); return; }
      if (e.key === "Escape" && !this.menuOn) return; // browser owns Esc for leaving full screen
      if (this.shadowRoot.activeElement && this.shadowRoot.activeElement.id === "gq" && e.key === "ArrowDown") { e.preventDefault(); return this.focusCol("g"); }
      if (this.shadowRoot.activeElement && this.shadowRoot.activeElement.tagName === "BUTTON" && !this.menuOn && e.key === "Enter" && this.shadowRoot.activeElement.closest(".remote,.fab")) return; // let a focused remote button act
      e.preventDefault(); this.poke(); this.press(k);
    };
    window.addEventListener("keydown", this._kd);
    this.$("gq").addEventListener("input", () => this.renderGroups());
    const wake = () => this.poke();
    this.stage.addEventListener("mousemove", wake); this.stage.addEventListener("touchstart", wake, { passive: true });
    this.stage.addEventListener("dblclick", (e) => { if (!e.target.closest(".remote,.fab,.menu")) this.fullscreen(); });
    this.v.addEventListener("click", () => { if (!this.menuOn) this.poke(); });
    this.v.addEventListener("waiting", () => { if (this.cur) this.setState("loading", "Buffering…"); });
    this.v.addEventListener("playing", () => { this.setState(null); this.sync(); });
    this.v.addEventListener("pause", () => this.sync()); this.v.addEventListener("volumechange", () => this.sync());
    this.$("hot").addEventListener("mouseenter", () => this.showRemote(true));
    this.$("hot").addEventListener("mouseleave", () => { if (!this.pinned) { clearTimeout(this._rt); this._rt = setTimeout(() => this.showRemote(false), 3000); } });
    this.$("fab").addEventListener("click", () => { this.pinned = !this.pinned; this.$("fab").classList.toggle("pinned", this.pinned); this.$("fab").title = this.pinned ? "Unpin remote" : "Pin remote"; this.showRemote(true); if (!this.pinned) this.poke(); });
    this.$("remote").addEventListener("click", (e) => { const b = e.target.closest("button[data-k]"); if (b) { this.press(b.dataset.k); this.poke(); } });
    this.tokenTimer = setInterval(() => { if (this._hass && this._hass.auth.expired) this._hass.auth.refreshAccessToken().catch(() => {}); }, 60000);
    this.poke();
    if (!this.saved.seenTip) { const t = this.$("tip"); t.classList.add("on"); setTimeout(() => t.classList.remove("on"), 6000); try { localStorage.setItem(LS, JSON.stringify({ ...this.saved, seenTip: 1 })); } catch (e) {} }
  }
  disconnectedCallback() {
    window.removeEventListener("keydown", this._kd); clearInterval(this.tokenTimer);
    if (this.hls) this.hls.destroy(); this.v.pause();
  }
}
customElements.define("ha-iptv-player", HaIptvPlayer);
