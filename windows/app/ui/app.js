"use strict";
(() => {
  const T = window.__TAURI__;
  const invoke = T ? T.core.invoke : () => Promise.reject(new Error("no host"));
  const $ = (id) => document.getElementById(id);

  let snap = null;
  let lang = "fa";
  let updateReady = false; // a newer build exists
  let received = 0; // Date.now() when the last snapshot arrived

  const t = (key, ...args) => {
    const s = (window.I18N[lang] && window.I18N[lang][key]) ?? window.I18N.en[key] ?? key;
    return s.replace(/\{(\d+)\}/g, (_, i) => args[i] ?? "");
  };
  const num = (n) => new Intl.NumberFormat(lang === "fa" ? "fa-IR" : "en-US", { useGrouping: false }).format(n);

  function applyStatic() {
    document.documentElement.lang = lang;
    document.documentElement.dir = lang === "fa" ? "rtl" : "ltr";
    document.querySelectorAll("[data-i18n]").forEach((el) => (el.textContent = t(el.dataset.i18n)));
    document.querySelectorAll("[data-i18n-title]").forEach((el) => (el.title = t(el.dataset.i18nTitle)));
  }

  function fmtDuration(sec) {
    const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60);
    const parts = [];
    if (h) parts.push(`${num(h)} ${t("unit_hours")}`);
    if (m || !h) parts.push(`${num(m)} ${t("unit_minutes")}`);
    return parts.join(lang === "fa" ? " و " : " ");
  }

  function fmtDate(ms) {
    return new Intl.DateTimeFormat(lang === "fa" ? "fa-IR" : "en-GB", { dateStyle: "medium" }).format(new Date(ms));
  }

  function noticeText(n) {
    switch (n.kind) {
      case "quota_exhausted": return t("notice_quota_exhausted");
      case "blocked": return t("notice_blocked");
      case "session_ended": return t("notice_session_ended");
      case "connection_lost": return t("notice_connection_lost");
      case "network_lost": return t("notice_network_lost");
      case "server_unavailable": return t("notice_server_unavailable");
      case "engine_failed": return t("notice_engine_failed", n.reason);
      case "service_error": return t("notice_service_error", n.message);
      default: return "";
    }
  }

  // Seconds shown in the tiles and the note below them, for the current moment.
  function timerValue() {
    const a = snap.account;
    const conn = snap.connection;
    if (!a) return { sec: 0, note: t("loading"), unlimited: false };
    if (a.is_vip) {
      if (a.vip_lifetime || !a.vip_expires_at_ms) return { sec: 0, unlimited: true, note: a.vip_lifetime ? t("vip_lifetime") : t("vip_unlimited") };
      const left = Math.max(0, Math.floor((a.vip_expires_at_ms - Date.now()) / 1000));
      return { sec: left, note: t("vip_until", fmtDate(a.vip_expires_at_ms)) };
    }
    let sec = conn.remaining_seconds ?? a.remaining_seconds;
    if (conn.phase === "connected" && conn.remaining_seconds != null) {
      sec = Math.max(0, conn.remaining_seconds - Math.floor((Date.now() - received) / 1000));
    }
    let note = t("time_card_note");
    if (sec <= 0 && a.resets_at_ms) note = t("resets_in", fmtDuration(Math.max(0, Math.floor((a.resets_at_ms - Date.now()) / 1000))));
    return { sec, note };
  }

  function renderTimer() {
    if (!snap) return;
    const v = timerValue();
    const set = (id, x) => ($(id).textContent = v.unlimited ? "∞" : num(x));
    set("t-d", Math.floor(v.sec / 86400));
    set("t-h", Math.floor((v.sec % 86400) / 3600));
    set("t-m", Math.floor((v.sec % 3600) / 60));
    set("t-s", v.sec % 60);
    $("timer-note").textContent = v.note;
  }

  function renderMessages() {
    const box = $("messages");
    const lines = [];
    const conn = snap.connection;
    const a = snap.account;
    if (conn.notice) lines.push({ text: noticeText(conn.notice), action: conn.phase === "idle" ? "dismiss" : null });
    if (snap.load_error && !snap.loading) lines.push({ text: t("load_error"), action: "retry" });
    if (a && a.is_blocked && !(conn.notice && conn.notice.kind === "blocked")) lines.push({ text: t("notice_blocked") });
    if (a && !a.is_vip && a.remaining_seconds <= 0 && conn.phase === "idle" && !(conn.notice && conn.notice.kind === "quota_exhausted")) lines.push({ text: t("free_time_over") });
    if (snap.update_available) lines.push({ text: t("update_required") });
    if (updateReady) lines.push({ text: t("update_new"), action: "update" });
    if (!snap.loading && !snap.load_error && snap.servers.length && !snap.servers.some((s) => s.supported)) lines.push({ text: t("no_servers") });
    box.hidden = lines.length === 0;
    box.replaceChildren(
      ...lines.map((l) => {
        const p = document.createElement("p");
        p.insertAdjacentHTML("afterbegin", '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16.5v.01"/></svg>');
        const span = document.createElement("span"); span.textContent = l.text; p.append(span);
        if (l.action === "update") {
          const b = document.createElement("button");
          b.className = "btn"; b.textContent = t("update_now");
          b.onclick = (e) => { e.stopPropagation(); doUpdate(); };
          span.append(document.createElement("br"), b);
        }
        if (l.action === "retry") {
          const b = document.createElement("button");
          b.className = "btn"; b.textContent = t("retry");
          b.onclick = () => invoke("refresh");
          span.append(document.createElement("br"), b);
        }
        return p;
      })
    );
  }

  function renderServers() {
    // The server can be changed while connected: the connection then moves to the new server.
    const busy = snap.connection.phase === "disconnecting";
    const label = (sv) => `${lang === "fa" ? sv.country_name_fa : sv.country_name_en} — ${sv.name}`;
    const cur = snap.servers.find((x) => x.id === snap.selected_server_id);
    $("srv-title").textContent = cur ? label(cur) : t("choose_server");
    const box = $("srv-items");
    box.replaceChildren(
      ...snap.servers.map((sv) => {
        const b = document.createElement("button");
        b.type = "button"; b.className = "lm-item"; b.tabIndex = -1; b.disabled = !sv.supported;
        if (sv.id === snap.selected_server_id) b.dataset.current = "1";
        const r = document.createElement("span"); r.className = "roll";
        const text = label(sv) + (sv.supported ? "" : ` (${t("coming_soon")})`);
        for (let i = 0; i < 2; i++) { const x = document.createElement("span"); x.textContent = text; r.append(x); }
        b.append(r);
        b.onclick = () => { setSrv(false); if (sv.id !== snap.selected_server_id) invoke("select_server", { id: sv.id }); };
        return b;
      })
    );
    const n = Math.max(1, snap.servers.length);
    $("srv").style.setProperty("--h", Math.min(48 + n * 44 + 12, 320) + "px");
    $("srv-bar").disabled = busy || snap.servers.length === 0;
    if (busy) setSrv(false);
  }

  // The status words change without any motion.
  function setStatus(text) {
    const box = $("status");
    box.querySelector(".st-a").textContent = text;
    box.querySelector(".st-b").textContent = "";
  }

  function setSrv(open) {
    $("srv").classList.toggle("open", open);
    $("srv-bar").setAttribute("aria-expanded", String(open));
    document.querySelectorAll("#srv-items .lm-item").forEach((b) => (b.tabIndex = open ? 0 : -1));
  }

  // ---- lever switch: spring-driven, mirrors LeverSwitch.kt (118x40 base, 52 long lever, r17 ball) ----
  const lever = { p: 0, v: 0, target: 0, raf: 0 };
  function drawLever(p) {
    const px = 33 + 118 * (0.22 + 0.56 * p), py = 78;
    const deg = (p - 0.5) * 2 * 18, rad = (deg * Math.PI) / 180;
    const tx = px + Math.sin(rad) * 52, ty = py - Math.cos(rad) * 52;
    $("lv-lever").setAttribute("transform", `translate(${px} ${py}) rotate(${deg})`);
    $("lv-lever-shadow").setAttribute("transform", `translate(${px + 3} ${py + 1.5}) rotate(${deg})`);
    $("lv-ball").setAttribute("transform", `translate(${tx} ${ty})`);
    $("lv-ball-shadow").setAttribute("transform", `translate(${tx + 5} ${py + 3})`);
  }
  // Compose spring(DampingRatioLowBouncy = 0.75, StiffnessLow = 200)
  function moveLever(target) {
    lever.target = target;
    if (matchMedia("(prefers-reduced-motion: reduce)").matches) { lever.p = target; lever.v = 0; drawLever(target); return; }
    if (lever.raf) return;
    const k = 200, c = 2 * 0.75 * Math.sqrt(k);
    let last = performance.now();
    const step = (now) => {
      let dt = Math.min((now - last) / 1000, 0.032); last = now;
      for (let t = 0; t < dt; t += 0.004) { const h = Math.min(0.004, dt - t); lever.v += (-k * (lever.p - lever.target) - c * lever.v) * h; lever.p += lever.v * h; }
      drawLever(lever.p);
      if (Math.abs(lever.v) < 0.002 && Math.abs(lever.p - lever.target) < 0.0005) { lever.p = lever.target; drawLever(lever.p); lever.raf = 0; return; }
      lever.raf = requestAnimationFrame(step);
    };
    lever.raf = requestAnimationFrame(step);
  }

  // ---- status banner: busy states stay, "connected" fades after 3 s, idle is hidden (as Android's StatusBanner) ----
  let bannerTimer = 0, bannerPhase = null;
  function renderBanner(phase, text) {
    const banner = $("banner");
    if (phase !== bannerPhase) {
      clearTimeout(bannerTimer);
      bannerPhase = phase;
      if (phase === "connected") { banner.classList.add("show"); bannerTimer = setTimeout(() => banner.classList.remove("show"), 3000); }
      else banner.classList.toggle("show", phase !== "idle");
    }
    $("status-text").textContent = phase === "idle" ? "" : text;
    setStatus(text);
  }

  function renderConnect() {
    const c = snap.connection;
    const a = snap.account;
    const btn = $("btn-connect");
    const phase = c.phase;
    const busy = phase === "starting" || phase === "connecting" || phase === "disconnecting";
    const on = phase === "connected";
    btn.dataset.phase = phase;
    btn.classList.toggle("on", on);
    btn.setAttribute("aria-checked", String(on));
    moveLever(busy ? 0.5 : on ? 1 : 0);
    const quotaOver = !!a && !a.is_vip && a.remaining_seconds <= 0;
    const canStart = !!snap.selected_server_id && !!a && !a.is_blocked && !quotaOver;
    btn.disabled = phase === "disconnecting" || (phase === "idle" && !canStart);
    btn.setAttribute("aria-label", t(on || busy ? "hint_tap_to_disconnect" : "hint_tap_to_connect"));
    // Android shows the same words for "starting" and "connecting".
    renderBanner(phase, t("status_" + (phase === "idle" ? "disconnected" : phase === "starting" ? "connecting" : phase)));
  }

  drawLever(0);

  function render() {
    if (!snap) return;
    if (snap.language !== lang) { lang = snap.language; applyStatic(); }
    document.documentElement.dataset.theme = snap.theme;
    renderServers();
    renderConnect();
    renderMessages();
    renderTimer();
    $("id-row").hidden = !snap.account;
    if (snap.account) $("user-code").textContent = snap.account.user_code;
    // A VIP has nothing to buy: the slot becomes "share" (same as Android).
    const vip = $("btn-vip");
    const isVip = !!snap.account && snap.account.is_vip;
    vip.textContent = isVip ? t("share_short") : t("buy_vip");
    vip.dataset.action = isVip ? "share" : "vip";
    const support = $("btn-support");
    support.textContent = snap.update_available ? t("update_app") : t("support");
    support.dataset.link = snap.update_available ? "update" : "support";
  }

  // Fixed install links (nothing here is opened by the host; the text is only shared or copied).
  const SHARE_WIN = "https://github.com/bashirrazmjoo-dotcom/fastgame-vpn/releases/download/windows-latest/FastGame-Setup.exe";
  const SHARE_ANDROID = "https://github.com/bashirrazmjoo-dotcom/fastgame-vpn/releases/download/stable/FastGameVPN.apk";
  // Copies the install links; the toast confirms it (Windows has no reliable share sheet in the webview).
  async function share() {
    const text = t("share_text", SHARE_WIN, SHARE_ANDROID);
    try {
      await navigator.clipboard.writeText(text);
      toast(t("share_copied"));
    } catch (_) {
      // Clipboard blocked: show the text so it can be copied by hand.
      $("licenses-text").textContent = text;
      $("licenses-dialog").querySelector("h3").textContent = t("share_app");
      $("licenses-dialog").showModal();
    }
  }

  function setMenu(open) {
    $("menu-panel").hidden = !open;
    $("btn-menu").setAttribute("aria-expanded", String(open));
  }

  function setUpdateReady(v) {
    updateReady = v;
    $("update-dot").hidden = !v;
    if (snap) renderMessages();
  }
  async function doUpdate() {
    toast(t("update_downloading"), 60000);
    try { await invoke("install_update"); } catch (_) { toast(t("update_failed"), 4000); }
  }
  async function updateFromMenu() {
    if (updateReady) return doUpdate();
    toast(t("update_checking"), 15000);
    try {
      const newer = await invoke("check_update");
      setUpdateReady(newer);
      if (newer) { toast(t("update_new"), 2500); doUpdate(); } else toast(t("update_none"), 2500);
    } catch (_) { toast(t("update_check_failed"), 3000); }
  }

  function toast(text, ms = 1800) {
    const el = $("toast");
    el.textContent = text; el.hidden = false;
    clearTimeout(toast.h);
    toast.h = setTimeout(() => (el.hidden = true), ms);
  }

  function onSnapshot(s) { snap = s; received = Date.now(); render(); }

  $("btn-connect").onclick = () => {
    const phase = snap.connection.phase;
    invoke(phase === "idle" ? "connect" : "disconnect");
  };
  $("btn-menu").onclick = (e) => { e.stopPropagation(); setMenu($("menu-panel").hidden); };
  document.addEventListener("click", (e) => { if (!e.target.closest(".menu")) setMenu(false); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") { setMenu(false); setSrv(false); } });
  $("srv-bar").onclick = (e) => { e.stopPropagation(); setSrv(!$("srv").classList.contains("open")); };
  document.addEventListener("click", (e) => { if (!e.target.closest(".srv")) setSrv(false); });
  $("btn-lang").onclick = () => { setMenu(false); invoke("set_language", { lang: lang === "fa" ? "en" : "fa" }); };
  $("btn-theme").onclick = () => { setMenu(false); invoke("set_theme", { theme: snap.theme === "dark" ? "light" : "dark" }); };
  $("btn-vip").onclick = (e) => (e.currentTarget.dataset.action === "share" ? share() : invoke("open_link", { link: "vip" }));
  $("btn-share-round").onclick = () => { setMenu(false); share(); };
  $("btn-update").onclick = () => { setMenu(false); updateFromMenu(); };
  $("btn-support").onclick = (e) => invoke("open_link", { link: e.currentTarget.dataset.link });
  $("btn-privacy").onclick = () => invoke("open_link", { link: "privacy" });
  // Copy button: the row blurs out, a confirmation with a progress fill shows for 4 s, then it returns.
  let ccTimers = [];
  $("btn-copy").onclick = async () => {
    const code = snap.account.user_code;
    try { await navigator.clipboard.writeText(code); } catch (_) {
      const ta = document.createElement("textarea");
      ta.value = code; ta.style.cssText = "position:fixed;left:-9999px;top:-9999px";
      document.body.appendChild(ta); ta.select(); try { document.execCommand("copy"); } catch (_) {} ta.remove();
    }
    const row = $("id-row"), bar = $("cc-progress");
    ccTimers.forEach(clearTimeout); ccTimers = [];
    row.classList.remove("confirm"); row.classList.add("copied");
    bar.style.transition = "none"; bar.style.width = "0%"; void bar.offsetWidth;
    bar.style.transition = "width 4s linear, opacity .5s"; bar.style.width = "100%";
    ccTimers.push(setTimeout(() => row.classList.add("confirm"), 400));
    ccTimers.push(setTimeout(() => row.classList.remove("confirm"), 4000));
    ccTimers.push(setTimeout(() => { row.classList.remove("copied"); bar.style.transition = "none"; bar.style.width = "0%"; }, 4400));
  };
  $("btn-licenses").onclick = () => { $("licenses-dialog").querySelector("h3").textContent = t("licenses"); $("licenses-text").textContent = t("licenses_body"); $("licenses-dialog").showModal(); };
  $("btn-licenses-close").onclick = () => $("licenses-dialog").close();
  $("messages").addEventListener("click", () => { if (snap.connection.notice && snap.connection.phase === "idle") invoke("dismiss_notice"); });

  // Cards: soft cursor light and a slight 3D tilt (skipped for reduced motion).
  if (!matchMedia("(prefers-reduced-motion: reduce)").matches) {
    document.querySelectorAll(".card").forEach((k) => {
      k.addEventListener("pointermove", (e) => {
        const r = k.getBoundingClientRect();
        const px = (e.clientX - r.left) / r.width - 0.5, py = (e.clientY - r.top) / r.height - 0.5;
        k.style.setProperty("--mx", `${e.clientX - r.left}px`); k.style.setProperty("--my", `${e.clientY - r.top}px`);
        if (!k.querySelector(".open")) k.style.transform = `perspective(900px) rotateX(${(-py * 5).toFixed(2)}deg) rotateY(${(px * 5).toFixed(2)}deg)`;
      });
      k.addEventListener("pointerleave", () => { k.style.transform = ""; });
    });
  }

  setInterval(renderTimer, 1000);

  applyStatic();
  if (T) {
    T.event.listen("snapshot", (e) => onSnapshot(e.payload));
    T.event.listen("update_found", () => setUpdateReady(true));
    invoke("snapshot").then(onSnapshot).then(() => invoke("refresh"));
  }
  window.__fgRender = onSnapshot; // used by the visual tests
})();
