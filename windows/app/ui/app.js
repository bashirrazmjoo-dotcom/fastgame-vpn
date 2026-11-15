"use strict";
(() => {
  const T = window.__TAURI__;
  const invoke = T ? T.core.invoke : () => Promise.reject(new Error("no host"));
  const $ = (id) => document.getElementById(id);

  let snap = null;
  let lang = "fa";
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
    if (!snap.loading && !snap.load_error && snap.servers.length && !snap.servers.some((s) => s.supported)) lines.push({ text: t("no_servers") });
    box.hidden = lines.length === 0;
    box.replaceChildren(
      ...lines.map((l) => {
        const p = document.createElement("p");
        p.textContent = l.text;
        if (l.action === "retry") {
          const b = document.createElement("button");
          b.className = "btn"; b.textContent = t("retry");
          b.onclick = () => invoke("refresh");
          p.append(document.createElement("br"), b);
        }
        return p;
      })
    );
  }

  function renderServers() {
    const sel = $("server");
    const busy = snap.connection.phase !== "idle";
    const want = snap.selected_server_id;
    sel.replaceChildren(
      ...snap.servers.map((s) => {
        const o = document.createElement("option");
        o.value = s.id;
        const country = lang === "fa" ? s.country_name_fa : s.country_name_en;
        o.textContent = `${country} — ${s.name}` + (s.supported ? "" : ` (${t("coming_soon")})`);
        o.disabled = !s.supported;
        o.selected = s.id === want;
        return o;
      })
    );
    sel.disabled = busy || snap.servers.length === 0;
  }

  function renderConnect() {
    const c = snap.connection;
    const a = snap.account;
    const btn = $("btn-connect");
    const phase = c.phase;
    btn.dataset.phase = phase;
    btn.dataset.busy = String(phase === "starting" || phase === "connecting" || phase === "disconnecting");
    const quotaOver = !!a && !a.is_vip && a.remaining_seconds <= 0;
    const canStart = !!snap.selected_server_id && !!a && !a.is_blocked && !quotaOver;
    btn.disabled = phase === "disconnecting" || (phase === "idle" && !canStart);
    $("status").textContent = t("status_" + (phase === "idle" ? "disconnected" : phase));
    $("hint").textContent = phase === "idle" ? (canStart ? t("hint_tap_to_connect") : "") : phase === "disconnecting" ? "" : t("hint_tap_to_disconnect");
    btn.setAttribute("aria-label", $("status").textContent);
  }

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
    $("lm").classList.toggle("open", open);
    $("btn-menu").setAttribute("aria-expanded", String(open));
    document.querySelectorAll(".lm-item").forEach((b) => (b.tabIndex = open ? 0 : -1));
  }

  function toast(text) {
    const el = $("toast");
    el.textContent = text; el.hidden = false;
    clearTimeout(toast.h);
    toast.h = setTimeout(() => (el.hidden = true), 1800);
  }

  function onSnapshot(s) { snap = s; received = Date.now(); render(); }

  $("btn-connect").onclick = () => {
    const phase = snap.connection.phase;
    invoke(phase === "idle" ? "connect" : "disconnect");
  };
  $("server").onchange = (e) => invoke("select_server", { id: Number(e.target.value) });
  $("btn-menu").onclick = (e) => { e.stopPropagation(); setMenu(!$("lm").classList.contains("open")); };
  document.addEventListener("click", (e) => { if (!e.target.closest(".menu")) setMenu(false); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") setMenu(false); });
  $("btn-lang").onclick = () => { setMenu(false); invoke("set_language", { lang: lang === "fa" ? "en" : "fa" }); };
  $("btn-theme").onclick = () => { setMenu(false); invoke("set_theme", { theme: snap.theme === "dark" ? "light" : "dark" }); };
  $("btn-vip").onclick = (e) => (e.currentTarget.dataset.action === "share" ? share() : invoke("open_link", { link: "vip" }));
  $("btn-share-round").onclick = () => { setMenu(false); share(); };
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

  setInterval(renderTimer, 1000);

  applyStatic();
  if (T) {
    T.event.listen("snapshot", (e) => onSnapshot(e.payload));
    invoke("snapshot").then(onSnapshot).then(() => invoke("refresh"));
  }
  window.__fgRender = onSnapshot; // used by the visual tests
})();
