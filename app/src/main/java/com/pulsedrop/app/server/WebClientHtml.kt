package com.pulsedrop.app.server

object WebClientHtml {

    /** The device name is user-editable in the app, so it must not break out of the markup. */
    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    fun getHtml(deviceName: String, requiresPin: Boolean): String {
        val safeDeviceName = esc(deviceName)
        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
  <title>PulseDrop - Private Local File Share</title>
  <style>
    /* ===== Premium light app shell. No CDN, no dark mode, no gradients-as-decoration. ===== */
    :root {
      --accent: #2563EB;
      --accent-hover: #1D4ED8;
      --accent-tint: #EFF6FF;
      --text: #0F172A;
      --muted: #64748B;
      --bg: #F8FAFC;
      --surface: #FFFFFF;
      --hairline: #E2E8F0;
      --hairline-strong: #CBD5E1;
      --error: #DC2626;
      --error-tint: #FEE2E2;
      --success: #059669;
      --t-blue-bg: #EFF6FF;   --t-blue-fg: #1D4ED8;
      --t-green-bg: #D1FAE5;  --t-green-fg: #047857;
      --t-violet-bg: #EDE9FE; --t-violet-fg: #6D28D9;
      --t-amber-bg: #FEF3C7;  --t-amber-fg: #B45309;
      --t-slate-bg: #E2E8F0;  --t-slate-fg: #334155;
      --shadow-1: 0 1px 2px rgba(15,23,42,0.04), 0 1px 3px rgba(15,23,42,0.06);
      --shadow-2: 0 6px 20px rgba(15,23,42,0.09);
      --radius: 16px;
      --topbar-h: 52px;
      --tabbar-h: 60px;
    }

    * { box-sizing: border-box; margin: 0; padding: 0; }
    html, body { height: 100%; }
    body {
      font-family: "Inter", system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
      background: var(--bg);
      color: var(--text);
      -webkit-font-smoothing: antialiased;
      overflow: hidden;
    }
    svg { display: block; flex-shrink: 0; }
    button, input, textarea { font-family: inherit; }
    :focus-visible {
      outline: 3px solid rgba(37,99,235,0.45);
      outline-offset: 2px;
      border-radius: 10px;
    }
    @media (prefers-reduced-motion: reduce) {
      *, *::before, *::after { transition: none !important; animation: none !important; }
    }

    .app { display: flex; flex-direction: column; height: 100dvh; }

    /* ===== Slim top bar: connection state + server URL ===== */
    .topbar {
      height: var(--topbar-h);
      flex-shrink: 0;
      background: var(--surface);
      border-bottom: 1px solid var(--hairline);
      display: flex; align-items: center; gap: 12px;
      padding: 0 16px;
    }
    .status-pill {
      display: inline-flex; align-items: center; gap: 7px;
      background: var(--t-green-bg); color: var(--t-green-fg);
      border-radius: 999px; padding: 6px 12px;
      font-size: 12px; font-weight: 600; white-space: nowrap;
    }
    .status-pill.off { background: var(--error-tint); color: var(--error); }
    .status-pill .dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
    .topbar-device { font-size: 13.5px; font-weight: 700; letter-spacing: -0.2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .topbar-url {
      margin-left: auto; display: inline-flex; align-items: center; gap: 6px;
      background: transparent; border: 1px solid var(--hairline); border-radius: 10px;
      color: var(--muted); font-size: 12px; font-weight: 600;
      padding: 6px 10px; cursor: pointer; white-space: nowrap; max-width: 46vw; overflow: hidden;
    }
    .topbar-url:hover { background: var(--accent-tint); color: var(--accent); border-color: var(--accent); }
    .topbar-url span { overflow: hidden; text-overflow: ellipsis; }

    /* ===== Two-pane shell ===== */
    .shell { flex: 1; min-height: 0; display: flex; }

    .pane-files {
      width: 320px; flex-shrink: 0;
      background: var(--surface);
      border-right: 1px solid var(--hairline);
      display: flex; flex-direction: column; min-height: 0;
      overflow-y: auto; overscroll-behavior: contain;
      padding: 16px 14px 24px;
      gap: 16px;
    }
    .pane-chat { flex: 1; min-width: 0; display: flex; flex-direction: row; min-height: 0; background: var(--bg); }

    .brand { display: flex; align-items: center; gap: 10px; padding: 0 4px; }
    .brand-mark {
      width: 38px; height: 38px; border-radius: 12px;
      background: var(--accent); color: #fff;
      display: flex; align-items: center; justify-content: center;
    }
    .brand-name { font-size: 17px; font-weight: 700; letter-spacing: -0.3px; }
    .brand-name em { font-style: normal; color: var(--accent); }
    .brand-sub { font-size: 11px; color: var(--muted); margin-top: 1px; }

    /* ===== Stats grid ===== */
    .sec-title {
      font-size: 11px; font-weight: 700; letter-spacing: 0.7px; text-transform: uppercase;
      color: var(--muted); padding: 0 4px;
    }
    .stats { display: grid; grid-template-columns: repeat(auto-fill, minmax(132px, 1fr)); gap: 8px; }
    .stat {
      background: var(--surface); border: 1px solid var(--hairline);
      border-radius: 14px; padding: 10px;
      display: flex; align-items: center; gap: 9px;
      box-shadow: var(--shadow-1); min-width: 0;
    }
    .stat-icon {
      width: 32px; height: 32px; border-radius: 9px; flex-shrink: 0;
      display: flex; align-items: center; justify-content: center;
    }
    .ic-blue { background: var(--t-blue-bg); color: var(--t-blue-fg); }
    .ic-green { background: var(--t-green-bg); color: var(--t-green-fg); }
    .ic-violet { background: var(--t-violet-bg); color: var(--t-violet-fg); }
    .ic-amber { background: var(--t-amber-bg); color: var(--t-amber-fg); }
    .ic-slate { background: var(--t-slate-bg); color: var(--t-slate-fg); }
    .stat-body { min-width: 0; }
    .stat-label { font-size: 10px; font-weight: 700; letter-spacing: 0.5px; text-transform: uppercase; color: var(--muted); }
    .stat-value { font-size: 14px; font-weight: 700; margin-top: 1px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

    /* ===== Dropzone ===== */
    .dropzone {
      background: var(--surface); border: 2px dashed var(--hairline-strong);
      border-radius: 18px; padding: 20px 14px; text-align: center;
      cursor: pointer; display: flex; flex-direction: column; align-items: center; gap: 6px;
      transition: border-color 0.15s, background 0.15s;
    }
    .dropzone:hover, .dropzone.dragover { border-color: var(--accent); background: var(--accent-tint); }
    .dropzone-icon {
      width: 44px; height: 44px; border-radius: 14px;
      background: var(--accent-tint); color: var(--accent);
      display: flex; align-items: center; justify-content: center;
    }
    .dropzone h3 { font-size: 13.5px; font-weight: 700; }
    .dropzone p { font-size: 12px; color: var(--muted); line-height: 1.45; }
    .dz-actions { display: flex; gap: 8px; margin-top: 6px; }

    .btn {
      border: none; border-radius: 12px; cursor: pointer;
      font-size: 12.5px; font-weight: 600; padding: 9px 12px;
      display: inline-flex; align-items: center; justify-content: center; gap: 6px; white-space: nowrap;
    }
    .btn-filled { background: var(--accent); color: #fff; }
    .btn-filled:hover { background: var(--accent-hover); }
    .btn-quiet { background: var(--surface); color: var(--muted); border: 1px solid var(--hairline); }
    .btn-quiet:hover { background: var(--bg); color: var(--text); }

    /* ===== Upload progress ===== */
    .upload-card {
      display: none; flex-direction: column; gap: 8px;
      background: var(--surface); border: 1px solid var(--hairline);
      border-radius: var(--radius); padding: 12px 14px; box-shadow: var(--shadow-1);
    }
    .bar-bg { width: 100%; height: 8px; background: var(--hairline); border-radius: 4px; overflow: hidden; }
    .bar-fill { height: 100%; width: 0%; background: var(--accent); border-radius: 4px; transition: width 0.1s linear; }

    /* ===== Filter row ===== */
    .search-row { display: flex; align-items: center; gap: 8px; }
    .search {
      display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0;
      background: var(--bg); border: 1px solid var(--hairline); border-radius: 12px;
      padding: 8px 12px; color: var(--muted);
    }
    .search:focus-within { border-color: var(--accent); background: var(--surface); }
    .search input {
      border: none; background: transparent; outline: none;
      font-size: 13px; color: var(--text); width: 100%; min-width: 0;
    }
    .chips { display: flex; gap: 6px; flex-wrap: wrap; }
    .chip {
      appearance: none; border: 1px solid var(--hairline);
      background: var(--surface); color: var(--muted);
      border-radius: 999px; padding: 6px 12px; font-size: 12px; font-weight: 600;
      cursor: pointer; transition: background 0.15s, border-color 0.15s, color 0.15s;
    }
    .chip:hover { background: var(--bg); }
    .chip.active { background: var(--accent-tint); border-color: var(--accent); color: var(--accent); }

    /* ===== File list ===== */
    .file-list { display: flex; flex-direction: column; gap: 8px; }
    .card {
      background: var(--surface); border: 1px solid var(--hairline);
      border-radius: var(--radius); padding: 12px;
      display: flex; flex-direction: column; gap: 10px;
      transition: border-color 0.15s, box-shadow 0.15s;
    }
    .card:hover { border-color: var(--accent); box-shadow: var(--shadow-1); }
    .card-top { display: flex; align-items: center; gap: 10px; min-width: 0; }
    .tile {
      width: 40px; height: 40px; border-radius: 12px; flex-shrink: 0;
      display: flex; align-items: center; justify-content: center;
    }
    .file-name { font-weight: 600; font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .file-meta { font-size: 11.5px; color: var(--muted); margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .card-actions { display: flex; gap: 8px; }
    .dl-btn {
      flex: 1; display: flex; align-items: center; justify-content: center; gap: 6px; min-width: 0;
      background: var(--bg); color: var(--text);
      border: 1px solid var(--hairline); border-radius: 10px;
      padding: 8px 10px; font-size: 12px; font-weight: 600; text-decoration: none;
      transition: background 0.15s, color 0.15s, border-color 0.15s;
    }
    .dl-btn:hover { background: var(--accent); color: #fff; border-color: var(--accent); }
    .icon-btn {
      appearance: none; border: 1px solid var(--hairline);
      background: var(--bg); color: var(--muted);
      width: 34px; height: 34px; border-radius: 10px; cursor: pointer; flex-shrink: 0;
      display: flex; align-items: center; justify-content: center;
      transition: background 0.15s, color 0.15s, border-color 0.15s;
    }
    .icon-btn:hover { background: var(--error-tint); color: var(--error); border-color: var(--error); }
    .icon-btn.soft:hover { background: var(--accent-tint); color: var(--accent); border-color: var(--accent); }
    .folder-toggle {
      appearance: none; border: none; background: transparent; cursor: pointer;
      color: var(--accent); font-size: 12px; font-weight: 700; padding: 0; text-align: left;
    }
    .folder-toggle:hover { text-decoration: underline; }
    .folder-contents { display: none; flex-direction: column; gap: 5px; border-top: 1px dashed var(--hairline); padding-top: 8px; }
    .folder-contents.open { display: flex; }
    .f-row {
      display: flex; align-items: center; justify-content: space-between; gap: 8px;
      font-size: 12px; color: var(--text); background: var(--bg); border-radius: 8px; padding: 6px 9px;
    }
    .f-row span:first-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .f-row a { color: var(--accent); font-weight: 600; text-decoration: none; flex-shrink: 0; }
    .f-row a:hover { text-decoration: underline; }
    .f-row button { appearance: none; border: none; background: none; cursor: pointer; font: inherit; font-size: 12px; color: var(--muted); font-weight: 600; flex-shrink: 0; }
    .f-row button:hover { color: var(--accent); text-decoration: underline; }
    .empty {
      background: var(--surface); border: 1px solid var(--hairline); border-radius: var(--radius);
      padding: 34px 16px; text-align: center; color: var(--muted);
      display: flex; flex-direction: column; align-items: center; gap: 10px; font-size: 13px; line-height: 1.5;
    }
    .side-foot { font-size: 11px; color: var(--muted); line-height: 1.55; padding: 0 4px; display: flex; flex-direction: column; gap: 6px; }
    .privacy-note {
      background: var(--t-green-bg); color: var(--t-green-fg);
      border-radius: 12px; padding: 10px 12px; font-size: 11.5px; font-weight: 600;
      display: flex; align-items: center; gap: 8px;
    }

    /* ===== Chat: thread rail + conversation ===== */
    .rail {
      width: 292px; flex-shrink: 0; min-height: 0;
      display: flex; flex-direction: column;
      background: var(--surface); border-right: 1px solid var(--hairline);
    }
    .rail-head { padding: 14px 14px 10px; display: flex; align-items: center; gap: 8px; }
    .rail-title { font-size: 17px; font-weight: 800; letter-spacing: -0.5px; }
    .rail-title small { font-size: 11px; font-weight: 600; color: var(--muted); margin-left: 6px; letter-spacing: 0; }
    .icon-round {
      appearance: none; width: 34px; height: 34px; border-radius: 50%;
      border: 1px solid var(--hairline); background: var(--surface); color: var(--muted);
      display: inline-flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0;
      transition: background 0.15s, color 0.15s, border-color 0.15s;
    }
    .icon-round:hover { background: var(--accent-tint); color: var(--accent); border-color: var(--accent); }
    .rail-search { padding: 0 14px 10px; }
    .rail-search input {
      width: 100%; box-sizing: border-box; font-size: 13px; color: var(--text); outline: none;
      border: 1px solid var(--hairline); background: var(--bg); border-radius: 12px; padding: 9px 12px;
    }
    .rail-search input:focus { border-color: var(--accent); background: var(--surface); }
    .rail-new { display: none; gap: 6px; padding: 0 14px 10px; }
    .rail-new.open { display: flex; }
    .rail-new input {
      flex: 1; min-width: 0; font-size: 12.5px; color: var(--text); outline: none;
      border: 1px solid var(--accent); background: var(--surface); border-radius: 12px; padding: 8px 12px;
    }
    .rail-list { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 0 8px 10px; }
    .rail-empty { padding: 26px 16px; font-size: 12.5px; color: var(--muted); text-align: center; line-height: 1.55; }
    .rail-item {
      width: 100%; box-sizing: border-box; appearance: none; text-align: left; cursor: pointer;
      display: flex; align-items: center; gap: 10px; padding: 10px; border-radius: 14px;
      border: 1px solid transparent; background: none; color: var(--text); transition: background 0.12s;
    }
    .rail-item:hover { background: var(--bg); }
    .rail-item.active { background: var(--accent-tint); border-color: #C3D4FD; }
    .ravatar {
      width: 42px; height: 42px; border-radius: 50%; flex-shrink: 0;
      display: flex; align-items: center; justify-content: center;
      font-size: 15px; font-weight: 800; color: #fff; letter-spacing: -0.5px;
    }
    .rbody { flex: 1; min-width: 0; }
    .rtop { display: flex; align-items: baseline; gap: 6px; }
    .rname { font-size: 13.5px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .rtime { margin-left: auto; font-size: 10.5px; color: var(--muted); flex-shrink: 0; }
    .rprev { font-size: 12px; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 2px; }
    .rprev b { color: var(--text); font-weight: 600; }
    .rbadge {
      min-width: 19px; height: 19px; padding: 0 5px; border-radius: 999px; flex-shrink: 0;
      background: var(--accent); color: #fff; font-size: 10.5px; font-weight: 800;
      display: inline-flex; align-items: center; justify-content: center;
    }
    .tbadge {
      min-width: 18px; height: 18px; padding: 0 5px; border-radius: 999px;
      background: var(--accent); color: #fff; font-size: 10.5px; font-weight: 700;
      display: inline-flex; align-items: center; justify-content: center;
    }

    .convo { flex: 1; min-width: 0; display: flex; flex-direction: column; min-height: 0; }
    .convo-head {
      flex-shrink: 0; background: var(--surface); border-bottom: 1px solid var(--hairline);
      padding: 9px 12px; display: flex; align-items: center; gap: 10px;
    }
    .convo-id { flex: 1; min-width: 0; }
    .convo-title { font-size: 14.5px; font-weight: 700; letter-spacing: -0.2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .convo-sub { font-size: 11.5px; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 1px; }
    .convo-actions { display: flex; gap: 6px; align-items: center; }
    .convo-back { display: none; }
    .convo-note {
      flex-shrink: 0; display: none; gap: 8px; align-items: flex-start;
      padding: 9px 14px; font-size: 11.5px; line-height: 1.45; color: var(--muted);
      background: var(--bg); border-bottom: 1px solid var(--hairline);
    }
    .convo-note.show { display: flex; }

    .messages-wrap { position: relative; flex: 1; min-height: 0; }
    .messages {
      height: 100%; overflow-y: auto; overscroll-behavior: contain;
      padding: 16px 16px 8px; display: flex; flex-direction: column; gap: 10px;
    }
    .day-sep { align-self: center; font-size: 11px; font-weight: 600; color: var(--muted); background: var(--surface); border: 1px solid var(--hairline); border-radius: 999px; padding: 4px 12px; }
    .bubble-row { display: flex; align-items: flex-end; gap: 8px; max-width: 100%; }
    .bubble-row.mine { justify-content: flex-end; }
    .avatar {
      width: 28px; height: 28px; border-radius: 50%; flex-shrink: 0;
      background: var(--t-violet-bg); color: var(--t-violet-fg);
      display: flex; align-items: center; justify-content: center;
      font-size: 12px; font-weight: 700; margin-left: 36px;
    }
    .avatar.hidden { visibility: hidden; }
    .bubble-col { min-width: 0; max-width: min(76%, 560px); display: flex; flex-direction: column; align-items: flex-start; gap: 3px; }
    .bubble-row.mine .bubble-col { align-items: flex-end; }
    .bubble {
      max-width: 100%; padding: 10px 14px; border-radius: 18px;
      font-family: "Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji", "Twemoji Mozilla", sans-serif;
      font-size: 14px; line-height: 1.45; word-break: break-word; overflow-wrap: anywhere; white-space: pre-wrap;
      background: var(--hairline); color: var(--text);
      border-bottom-left-radius: 18px;
    }
    .bubble-row.mine .bubble { background: var(--accent); color: #fff; }
    .bubble-row.in:not(.group-cont) .bubble { border-bottom-left-radius: 6px; }
    .bubble-row.mine:not(.group-cont) .bubble { border-bottom-right-radius: 6px; }
    .bubble-meta { display: flex; align-items: center; gap: 8px; font-size: 11px; color: var(--muted); padding: 0 4px; min-height: 1px; }
    .bubble-row.mine .bubble-meta { color: var(--muted); }
    .meta-name { font-weight: 700; }
    .bubble-copy {
      appearance: none; border: none; background: transparent; cursor: pointer;
      font-size: 11px; font-weight: 700; color: var(--accent); padding: 0;
    }
    .bubble-copy:hover { text-decoration: underline; }
    .chat-empty {
      margin: auto; display: flex; flex-direction: column; align-items: center; gap: 10px;
      color: var(--muted); font-size: 13px; text-align: center; padding: 24px;
    }
    .new-pill {
      position: absolute; left: 50%; bottom: 14px; transform: translateX(-50%);
      display: none; align-items: center; gap: 6px;
      background: var(--accent); color: #fff; border: none; border-radius: 999px;
      padding: 8px 14px; font-size: 12px; font-weight: 600; cursor: pointer;
      box-shadow: var(--shadow-2); z-index: 5; white-space: nowrap;
    }
    .typing-line {
      flex-shrink: 0; min-height: 20px; font-size: 12px; color: var(--muted); font-style: italic;
      padding: 0 16px 2px; display: flex; align-items: center; gap: 4px;
    }
    .typing-dots { display: inline-flex; gap: 3px; }
    .typing-dots i { width: 4px; height: 4px; border-radius: 50%; background: var(--muted); animation: blink 1.2s infinite; }
    .typing-dots i:nth-child(2) { animation-delay: 0.2s; }
    .typing-dots i:nth-child(3) { animation-delay: 0.4s; }
    @keyframes blink { 0%, 60%, 100% { opacity: 0.25; } 30% { opacity: 1; } }

    .composer {
      flex-shrink: 0; background: var(--surface);
      border-top: 1px solid var(--hairline);
      padding: 10px 12px calc(10px + env(safe-area-inset-bottom));
      display: flex; gap: 8px; align-items: flex-end;
    }
    .composer textarea {
      flex: 1; min-width: 0; resize: none;
      background: var(--bg); border: 1px solid var(--hairline); border-radius: 14px;
      padding: 11px 14px; font-size: 14px; color: var(--text); outline: none;
      min-height: 44px; max-height: 120px; line-height: 1.4;
    }
    .composer textarea:focus { border-color: var(--accent); background: var(--surface); }
    .composer-wrap { position: relative; flex-shrink: 0; }
    .emoji-pop {
      display: none; position: absolute; left: 12px; right: 12px; bottom: calc(100% + 8px);
      background: var(--surface); border: 1px solid var(--hairline); border-radius: 16px;
      box-shadow: var(--shadow-2, 0 12px 32px rgba(15, 23, 42, 0.14));
      padding: 10px; max-height: 190px; overflow-y: auto;
      grid-template-columns: repeat(8, 1fr); gap: 2px; z-index: 6;
    }
    .emoji-pop.open { display: grid; }
    .emoji-pop button {
      appearance: none; border: none; background: none; cursor: pointer;
      font-size: 20px; line-height: 1; padding: 6px 2px; border-radius: 9px;
    }
    .emoji-pop button:hover { background: var(--bg); }
    .attach-btn.is-busy { opacity: 0.55; pointer-events: none; }
    .send-btn {
      width: 44px; height: 44px; border-radius: 14px; border: none; cursor: pointer; flex-shrink: 0;
      background: var(--accent); color: #fff;
      display: flex; align-items: center; justify-content: center;
      transition: background 0.15s;
    }
    .send-btn:hover { background: var(--accent-hover); }

    /* ===== Bottom tab bar (mobile) ===== */
    .tabbar {
      flex-shrink: 0; display: none;
      border-top: 1px solid var(--hairline); background: var(--surface);
      padding-bottom: env(safe-area-inset-bottom);
    }
    .tab-btn {
      flex: 1; appearance: none; border: none; background: transparent; cursor: pointer;
      height: var(--tabbar-h);
      display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px;
      color: var(--muted); font-size: 11px; font-weight: 600;
    }
    .tab-btn[aria-current="true"] { color: var(--accent); }
    .tab-wrap { position: relative; display: inline-flex; }
    .tab-dot {
      position: absolute; top: -3px; right: -8px;
      min-width: 16px; height: 16px; padding: 0 4px; border-radius: 999px;
      background: var(--accent); color: #fff; font-size: 10px; font-weight: 700;
      display: none; align-items: center; justify-content: center;
    }

    /* ===== Auth modal ===== */
    .overlay {
      position: fixed; inset: 0; background: rgba(15,23,42,0.45);
      display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px;
    }
    .modal {
      background: var(--surface); border-radius: 22px; padding: 28px;
      width: 100%; max-width: 380px; text-align: center;
      display: flex; flex-direction: column; gap: 14px; box-shadow: var(--shadow-2);
    }
    .modal-icon {
      width: 56px; height: 56px; border-radius: 17px; margin: 0 auto;
      background: var(--accent-tint); color: var(--accent);
      display: flex; align-items: center; justify-content: center;
    }
    .pin-input {
      background: var(--bg); border: 2px solid var(--hairline-strong); color: var(--text);
      font-size: 26px; letter-spacing: 10px; text-align: center; padding: 12px;
      border-radius: 14px; font-weight: 700; outline: none;
      font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
    }
    .pin-input:focus { border-color: var(--accent); }
    .pin-error { color: var(--error); background: var(--error-tint); border-radius: 10px; padding: 10px; font-size: 13px; font-weight: 600; display: none; }

    /* ===== Toast ===== */
    .toast {
      position: fixed; left: 50%; bottom: 24px;
      transform: translateX(-50%) translateY(14px);
      background: var(--text); color: #fff;
      font-size: 13px; font-weight: 600; padding: 11px 18px; border-radius: 12px;
      box-shadow: var(--shadow-2); opacity: 0; pointer-events: none;
      transition: opacity 0.2s, transform 0.2s; z-index: 1100; max-width: 90vw; text-align: center;
    }
    .toast.show { opacity: 1; transform: translateX(-50%) translateY(0); }

    /* ===== Left navigation rail ===== */
    .nav-rail {
      width: 76px; flex-shrink: 0; display: flex; flex-direction: column; align-items: center;
      gap: 6px; padding: 12px 0; background: var(--surface); border-right: 1px solid var(--hairline);
    }
    .nav-mark {
      width: 38px; height: 38px; border-radius: 13px; margin-bottom: 8px;
      background: var(--accent); color: #fff; display: flex; align-items: center; justify-content: center;
    }
    .nav-btn {
      appearance: none; border: none; background: none; cursor: pointer; width: 60px; padding: 8px 0 6px;
      border-radius: 14px; display: flex; flex-direction: column; align-items: center; gap: 4px;
      color: var(--muted); font-size: 10px; font-weight: 700; letter-spacing: 0.2px;
      transition: background 0.15s, color 0.15s;
    }
    .nav-btn:hover { background: var(--bg); color: var(--text); }
    .nav-btn.active { background: var(--accent-tint); color: var(--accent); }
    .nav-badge {
      position: absolute; top: -4px; right: -8px; min-width: 17px; height: 17px; padding: 0 4px;
      border-radius: 999px; background: var(--accent); color: #fff; font-size: 9.5px; font-weight: 800;
      display: none; align-items: center; justify-content: center;
    }
    .nav-ico { position: relative; display: inline-flex; }

    .view { display: none; flex: 1; min-width: 0; min-height: 0; }
    .view.active { display: flex; }
    .stage { flex: 1; min-width: 0; display: flex; flex-direction: column; min-height: 0; }
    .stage-head {
      flex-shrink: 0; display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
      padding: 14px 20px; background: var(--surface); border-bottom: 1px solid var(--hairline);
    }
    .stage-title { font-size: 17px; font-weight: 800; letter-spacing: -0.4px; }
    .stage-sub { font-size: 11.5px; color: var(--muted); margin-top: 2px; }
    .stage-body { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 16px 20px 24px; }
    .spacer { margin-left: auto; }
    .seg { display: inline-flex; border: 1px solid var(--hairline); border-radius: 11px; overflow: hidden; background: var(--surface); }
    .seg button {
      appearance: none; border: none; background: none; cursor: pointer; padding: 7px 11px;
      font-size: 11.5px; font-weight: 700; color: var(--muted);
    }
    .seg button.active { background: var(--accent); color: #fff; }
    .select {
      appearance: none; border: 1px solid var(--hairline); background: var(--surface); color: var(--text);
      border-radius: 11px; padding: 8px 12px; font-size: 12.5px; font-weight: 600; cursor: pointer;
    }
    .select:focus { outline: none; border-color: var(--accent); }
    .field-label { font-size: 10.5px; font-weight: 800; letter-spacing: 0.5px; text-transform: uppercase; color: var(--muted); }

    .grid-view { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; }
    .grid-view .card { height: 100%; }
    .list-view { display: flex; flex-direction: column; gap: 6px; }
    .list-view .card { padding: 10px 12px; }
    .list-view .card-top { flex: 1; min-width: 0; }

    .table { width: 100%; border-collapse: collapse; background: var(--surface); border: 1px solid var(--hairline); border-radius: var(--radius); overflow: hidden; }
    .table th {
      text-align: left; font-size: 10.5px; letter-spacing: 0.5px; text-transform: uppercase;
      color: var(--muted); padding: 11px 14px; border-bottom: 1px solid var(--hairline); background: var(--bg);
    }
    .table td { padding: 11px 14px; font-size: 13px; border-bottom: 1px solid var(--hairline); }
    .table tr:last-child td { border-bottom: none; }
    .table .who { font-weight: 700; }
    .table .ip { color: var(--muted); font-variant-numeric: tabular-nums; }
    .kv { display: grid; grid-template-columns: 150px 1fr; gap: 8px 14px; font-size: 13px; }
    .kv dt { color: var(--muted); font-weight: 600; }
    .kv dd { margin: 0; font-weight: 700; word-break: break-word; }
    .panel { background: var(--surface); border: 1px solid var(--hairline); border-radius: var(--radius); padding: 16px 18px; }
    .panel + .panel { margin-top: 14px; }
    .panel h3 { font-size: 13.5px; font-weight: 800; letter-spacing: -0.2px; margin-bottom: 12px; }

    /* ===== Call overlay ===== */
    .call-card { width: min(420px, 92vw); text-align: center; }
    .call-avatar {
      width: 84px; height: 84px; border-radius: 50%; margin: 0 auto 12px;
      display: flex; align-items: center; justify-content: center;
      font-size: 30px; font-weight: 800; color: #fff;
    }
    .call-title { font-size: 19px; font-weight: 800; letter-spacing: -0.4px; }
    .call-sub { font-size: 13px; color: var(--muted); margin-top: 4px; }
    .call-video { width: 100%; height: 190px; object-fit: cover; border-radius: 14px; background: #0F172A; margin-top: 14px; display: none; }
    .call-actions { display: flex; gap: 12px; justify-content: center; margin-top: 18px; }
    .call-btn {
      appearance: none; border: none; cursor: pointer; width: 58px; height: 58px; border-radius: 50%;
      display: inline-flex; align-items: center; justify-content: center; color: #fff; background: var(--hairline);
    }
    .call-btn.on { background: var(--accent); }
    .call-btn.end { background: var(--error); }
    .call-btn.off { background: var(--bg); color: var(--muted); border: 1px solid var(--hairline); }
    .call-note { font-size: 11.5px; color: var(--muted); margin-top: 14px; line-height: 1.5; }

    /* ===== Chat attachments: the file is the message ===== */
    .att-card {
      display: flex; align-items: center; gap: 10px;
      padding: 9px 10px; margin: 2px 0; max-width: 330px;
      border-radius: 12px; border: 1px solid var(--hairline);
      background: var(--surface); box-shadow: var(--shadow-1);
    }
    .att-thumb {
      width: 42px; height: 42px; flex: 0 0 42px; border-radius: 10px; overflow: hidden;
      display: inline-flex; align-items: center; justify-content: center;
    }
    .att-thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
    .att-body { flex: 1; min-width: 0; }
    .att-name {
      font-size: 13px; font-weight: 600; color: var(--text);
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .att-meta { font-size: 11.5px; color: var(--muted); margin-top: 2px; }
    .att-acts { display: flex; gap: 6px; flex: 0 0 auto; }
    .att-btn {
      font: inherit; font-size: 11.5px; font-weight: 600; cursor: pointer;
      padding: 5px 9px; border-radius: 8px; text-decoration: none; display: inline-flex; align-items: center;
      border: 1px solid var(--hairline); background: var(--bg); color: var(--text);
    }
    .att-btn:hover { background: var(--accent-tint); border-color: var(--accent); color: var(--accent); }
    .att-btn.prim { background: var(--accent); border-color: var(--accent); color: #fff; }
    .att-btn.prim:hover { background: var(--accent-hover); color: #fff; }

    /* ===== Preview lightbox ===== */
    .lb {
      position: fixed; inset: 0; z-index: 95; display: none; flex-direction: column;
      background: #0B1220;
    }
    .lb.open { display: flex; }
    .lb-head {
      display: flex; align-items: center; gap: 10px; padding: 9px 12px;
      border-bottom: 1px solid rgba(226,232,240,0.14); color: #E2E8F0; flex: 0 0 auto;
    }
    .lb-title { flex: 1; min-width: 0; font-size: 13.5px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .lb-sub { flex: 0 0 auto; font-size: 11.5px; color: #94A3B8; }
    .lb-tools { display: flex; align-items: center; gap: 5px; flex: 0 0 auto; }
    .lb-btn {
      font: inherit; width: 32px; height: 32px; border-radius: 9px; cursor: pointer;
      display: inline-flex; align-items: center; justify-content: center;
      border: 1px solid rgba(226,232,240,0.18); background: rgba(226,232,240,0.06); color: #E2E8F0;
    }
    .lb-btn:hover { background: rgba(226,232,240,0.16); }
    .lb-btn[disabled] { opacity: 0.35; cursor: default; }
    .lb-btn a, .lb-btn svg { display: block; }
    .lb-zoom { min-width: 48px; width: auto; padding: 0 6px; font-size: 11.5px; }
    .lb-stage { flex: 1; position: relative; overflow: hidden; display: flex; align-items: center; justify-content: center; }
    .lb-media {
      max-width: 94vw; max-height: calc(100vh - 120px); transform-origin: center center;
      will-change: transform; user-select: none; -webkit-user-drag: none; border-radius: 6px;
    }
    .lb-media.pan { cursor: grab; }
    .lb-media.pan.dragging { cursor: grabbing; }
    /* Images fill the stage so small files scale up instead of showing 1:1 pixels. */
    img.lb-media { width: 100%; height: 100%; max-width: none; max-height: none; object-fit: contain; }
    .lb-video { width: min(900px, 94vw); background: #000; }
    .lb-pdf { width: min(1000px, 94vw); height: calc(100vh - 120px); background: #fff; border: none; }
    .lb-audio-wrap { display: flex; flex-direction: column; align-items: center; gap: 16px; padding: 24px; }
    .lb-audio { width: min(520px, 86vw); }
    .lb-text { position: absolute; inset: 0; overflow: auto; padding: 16px 18px; }
    .lb-text pre {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 12.5px; line-height: 1.65; color: #E2E8F0; white-space: pre-wrap; word-break: break-word;
    }
    .lb-none { text-align: center; color: #CBD5E1; padding: 28px; max-width: 420px; }
    .lb-none .nm { font-size: 14px; font-weight: 600; margin-bottom: 4px; word-break: break-all; }
    .lb-none .mm { font-size: 12px; color: #94A3B8; margin-bottom: 16px; }
    .lb-hint {
      position: absolute; left: 0; right: 0; bottom: 10px; text-align: center;
      font-size: 11px; color: #64748B; pointer-events: none;
    }
    body.lb-block { overflow: hidden; }
    @media (max-width: 700px) {
      .lb-sub { display: none; }
      .lb-tools { gap: 3px; }
      .lb-btn { width: 30px; height: 30px; }
    }


    @media (min-width: 960px) {
      .tabbar { display: none !important; }
      .view.active { display: flex !important; }
    }

    /* ===== Mobile: single column + bottom tabs, chat fills viewport ===== */
    @media (max-width: 959px) {
      .topbar { padding: 0 12px; gap: 8px; }
      .topbar-device { max-width: 34vw; }
      .topbar-url { max-width: 30vw; }
      .tabbar { display: flex; }
      .nav-rail { display: none; }
      .shell { display: block; position: relative; }
      .view { height: 100%; }
      .view.active { display: flex !important; }
      .pane-files {
        width: auto; border-right: none;
        height: 100%; overflow-y: auto;
      }
      .pane-chat { height: 100%; }
      .pane-files[hidden], .pane-chat[hidden] { display: none !important; }
      .rail { width: 100%; border-right: none; }
      .pane-chat.open-thread .rail { display: none; }
      .pane-chat:not(.open-thread) .convo { display: none; }
      .convo-back { display: inline-flex; }
      .stats { grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); }
      .bubble-col { max-width: 82%; }
      .emoji-pop { grid-template-columns: repeat(6, 1fr); }
    }
  </style>
</head>
<body>

  <!-- PIN modal -->
  <div id="authOverlay" class="overlay" style="display: ${if (requiresPin) "flex" else "none"}">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="pinTitle">
      <div class="modal-icon">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="4" y="10" width="16" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/><circle cx="12" cy="15" r="1.5"/></svg>
      </div>
      <h2 id="pinTitle" style="font-size: 19px; font-weight: 700;">Enter security PIN</h2>
      <p style="color: var(--muted); font-size: 13.5px; line-height: 1.5;">This device is protected. Enter the PIN shown on ${safeDeviceName}.</p>
      <input id="pinInput" type="password" inputmode="numeric" maxlength="6" placeholder="----" class="pin-input" aria-label="Security PIN" autofocus onkeydown="if(event.key==='Enter')submitPin()" />
      <button onclick="submitPin()" class="btn btn-filled" style="width: 100%;" aria-label="Unlock device">Unlock</button>
      <p id="pinError" class="pin-error" role="alert">Invalid PIN. Please check the phone screen.</p>
    </div>
  </div>

  <div class="app" id="appRoot" style="display: ${if (requiresPin) "none" else "flex"}">

    <!-- Slim top bar -->
    <header class="topbar">
      <div class="status-pill" id="connPill" aria-live="polite"><span class="dot"></span> <span id="connText">Connecting…</span></div>
      <div class="topbar-device" id="topbarDevice">${safeDeviceName}</div>
      <button class="topbar-url" id="urlBtn" aria-label="Copy server address" onclick="copyServerUrl()">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"/></svg>
        <span id="serverUrl"></span>
      </button>
    </header>

    <div class="shell">

      <!-- ===== Navigation rail ===== -->
      <nav class="nav-rail" aria-label="Sections">
        <div class="nav-mark">
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v10"/><path d="m8 9 4 4 4-4"/><path d="M5 19h14"/></svg>
        </div>
        <button class="nav-btn active" id="navFiles" data-view="files" aria-current="true" onclick="setView('files')">
          <span class="nav-ico"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.6a2 2 0 0 1-1.7-.9L9.6 4.2A2 2 0 0 0 7.9 3.3H4a2 2 0 0 0-2 2v12.7A2 2 0 0 0 4 20Z"/></svg></span>
          Files
        </button>
        <button class="nav-btn" id="navChats" data-view="chats" aria-current="false" onclick="setView('chats')">
          <span class="nav-ico">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a8 8 0 0 1-8 8H5l-2 2V12a8 8 0 0 1 8-8h2a8 8 0 0 1 8 8z"/><path d="M9 11h6M9 14h4"/></svg>
            <span class="nav-badge" id="navChatsBadge">0</span>
          </span>
          Chats
        </button>
        <button class="nav-btn" id="navHistory" data-view="history" aria-current="false" onclick="setView('history')">
          <span class="nav-ico"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></svg></span>
          History
        </button>
        <button class="nav-btn" id="navDetails" data-view="details" aria-current="false" onclick="setView('details')">
          <span class="nav-ico"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3a14 14 0 0 1 0 18a14 14 0 0 1 0-18"/></svg></span>
          Details
        </button>
      </nav>

      <section class="view active" id="viewFiles">

      <!-- ===== Files pane (desktop sidebar / mobile Files tab) ===== -->
      <aside class="pane-files" id="paneFiles" aria-label="Files and stats">
        <div class="brand">
          <div class="brand-mark">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v10"/><path d="m8 9 4 4 4-4"/><path d="M5 19h14"/></svg>
          </div>
          <div>
            <div class="brand-name">Local<em>Drop</em></div>
            <div class="brand-sub" id="brandSub">Connected to ${safeDeviceName}</div>
          </div>
        </div>

        <div class="sec-title">Overview</div>
        <div class="stats" role="group" aria-label="Session statistics">
          <div class="stat"><div class="stat-icon ic-blue"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/></svg></div><div class="stat-body"><div class="stat-label">Files</div><div class="stat-value" id="statFiles">0</div></div></div>
          <div class="stat"><div class="stat-icon ic-green"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/></svg></div><div class="stat-body"><div class="stat-label">Total size</div><div class="stat-value" id="statSize">0 B</div></div></div>
          <div class="stat"><div class="stat-icon ic-violet"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a9 9 0 1 1-2.6-6.4"/><path d="M21 3v6h-6"/></svg></div><div class="stat-body"><div class="stat-label">Last sync</div><div class="stat-value" id="statSync">-</div></div></div>
          <div class="stat"><div class="stat-icon ic-amber"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="4" y="10" width="16" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg></div><div class="stat-body"><div class="stat-label">Access</div><div class="stat-value">${if (requiresPin) "PIN protected" else "Open"}</div></div></div>
          <div class="stat"><div class="stat-icon ic-blue"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="9" cy="8" r="3.2"/><path d="M3.5 19c.8-3 2.9-4.6 5.5-4.6s4.7 1.6 5.5 4.6"/><circle cx="17" cy="9" r="2.4"/><path d="M15.5 14.6c2.2.2 3.9 1.6 4.6 4"/></svg></div><div class="stat-body"><div class="stat-label">Visitors</div><div class="stat-value" id="statVisitors">0</div></div></div>
          <div class="stat"><div class="stat-icon ic-violet"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a8 8 0 0 1-8 8H5l-2 2V12a8 8 0 0 1 8-8h2a8 8 0 0 1 8 8z"/></svg></div><div class="stat-body"><div class="stat-label">Messages</div><div class="stat-value" id="statMessages">0</div></div></div>
          <div class="stat"><div class="stat-icon ic-green"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 10 12 5l5 5"/><path d="M12 5v14"/><path d="m17 20-5 5-5-5" transform="translate(0 -5)"/></svg></div><div class="stat-body"><div class="stat-label">Transfers</div><div class="stat-value" id="statTransfers">0</div></div></div>
          <div class="stat"><div class="stat-icon ic-amber"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></svg></div><div class="stat-body"><div class="stat-label">Uptime (session)</div><div class="stat-value" id="statUptime">0s</div></div></div>
          <div class="stat"><div class="stat-icon ic-slate"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3a14 14 0 0 1 0 18a14 14 0 0 1 0-18"/></svg></div><div class="stat-body"><div class="stat-label">Your IP</div><div class="stat-value" id="statYourIp">-</div></div></div>
        </div>

        <div id="dropzone" class="dropzone" role="button" tabindex="0" aria-label="Drop files here or press Enter to choose files">
          <div class="dropzone-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 13v8"/><path d="m8 17 4-4 4 4"/><path d="M20 8.8A5 5 0 0 0 15 5h-1.3A7 7 0 1 0 5 15.3"/></svg>
          </div>
          <h3>Drop files or folders</h3>
          <p>Fast local transfers. Nothing leaves your Wi-Fi network.</p>
          <div class="dz-actions">
            <button class="btn btn-filled" id="uploadBtn" aria-label="Upload files" onclick="event.stopPropagation(); pickFiles()">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 16V4"/><path d="m7 9 5-5 5 5"/><path d="M4 20h16"/></svg>
              Upload files
            </button>
            <button class="btn btn-quiet" id="uploadFolderBtn" aria-label="Upload folder" onclick="event.stopPropagation(); pickFolder()">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.6a2 2 0 0 1-1.7-.9L9.6 4.2A2 2 0 0 0 7.9 3.3H4a2 2 0 0 0-2 2v12.7A2 2 0 0 0 4 20Z"/><path d="M12 10v6M9 13h6"/></svg>
              Folder
            </button>
          </div>
        </div>

        <div id="uploadCard" class="upload-card" aria-live="polite">
          <div style="display: flex; justify-content: space-between; font-size: 12.5px; font-weight: 600;">
            <span id="uploadLabel">Uploading…</span>
            <span id="uploadPercent" style="color: var(--accent);">0%</span>
          </div>
          <div class="bar-bg"><div id="uploadBar" class="bar-fill"></div></div>
          <div style="display: flex; justify-content: space-between; font-size: 11.5px; color: var(--muted);">
            <span id="uploadBytes">0 B / 0 B</span>
            <span id="uploadSpeed">-</span>
          </div>
          <div style="display: flex; justify-content: flex-end;">
            <button id="cancelUploadBtn" class="btn btn-quiet" style="padding: 6px 12px; font-size: 11.5px; color: var(--error); border-color: var(--error);" aria-label="Cancel upload" onclick="cancelUpload()">Cancel upload</button>
          </div>
        </div>

        <div class="search-row">
          <div class="search">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
            <input id="searchInput" type="text" placeholder="Search files… (Ctrl+K)" aria-label="Search files" oninput="applyFilter()" />
          </div>
        </div>

        <div class="chips" id="chips">
          <button class="chip active" data-cat="all" aria-label="Show all files">All</button>
          <button class="chip" data-cat="docs" aria-label="Show documents">Documents</button>
          <button class="chip" data-cat="images" aria-label="Show images">Images</button>
          <button class="chip" data-cat="videos" aria-label="Show videos">Videos</button>
          <button class="chip" data-cat="audio" aria-label="Show audio">Audio</button>
          <button class="chip" data-cat="archives" aria-label="Show archives">Archives</button>
        </div>
        <div class="chips">
          <button class="chip" id="receivedChip" aria-pressed="false" aria-label="Filter to files received on the phone">On phone only</button>
        </div>

        <div class="side-foot">
          <div class="privacy-note">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3 4 6v5c0 5 3.4 8.6 8 10 4.6-1.4 8-5 8-10V6z"/><path d="m9 12 2 2 4-4"/></svg>
            All transfers stay on your local network. No cloud, no accounts, no tracking.
          </div>
          <div>Idle servers sleep after 30 minutes of no activity. Transfers stay on this Wi-Fi network.</div>
        </div>
      </aside>

      <div class="stage">
        <div class="stage-head">
          <div>
            <div class="stage-title">Hosted files</div>
            <div class="stage-sub" id="filesSub">Everything you drop is live on this server instantly.</div>
          </div>
          <div class="spacer"></div>
          <div>
            <div class="field-label" style="margin-bottom:4px">Send uploads to</div>
            <select class="select" id="targetSelect" aria-label="Choose which device receives the files" onchange="saveTarget()">
              <option value="">Everyone on the network</option>
            </select>
          </div>
          <div>
            <div class="field-label" style="margin-bottom:4px">Layout</div>
            <div class="seg" role="group" aria-label="File layout">
              <button id="layoutGrid" class="active" onclick="setLayout('grid')">Grid</button>
              <button id="layoutList" onclick="setLayout('list')">List</button>
            </div>
          </div>
        </div>
        <div class="stage-body">
          <div class="file-list grid-view" id="filesGrid"></div>
        </div>
      </div>
      </section>

      <!-- ===== Chat view: thread rail + conversation ===== -->
      <main class="view pane-chat" id="paneChat" aria-label="Chats">

        <section class="rail" id="threadRail" aria-label="Conversations">
          <div class="rail-head">
            <div class="rail-title">Chats<small id="railCount"></small></div>
            <button class="icon-round" id="railNewBtn" style="margin-left:auto" aria-label="Start a new conversation" aria-haspopup="true" onclick="toggleNewChatMenu(event)">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>
            </button>
          </div>
          <div class="rail-search">
            <input id="railSearch" type="text" placeholder="Search chats" aria-label="Search chats" oninput="renderThreadRail()" />
          </div>
          <div class="rail-new" id="railNew">
            <input id="newIpInput" type="text" inputmode="decimal" placeholder="Direct message an IP" aria-label="Device IP address" onkeydown="if(event.key==='Enter')startIpThread()" />
            <button class="btn btn-filled" style="padding:8px 12px;font-size:12.5px" onclick="startIpThread()">Start</button>
          </div>
          <div class="rail-list" id="threadList" role="list"></div>
        </section>

        <section class="convo" id="convoPane" aria-label="Conversation">
          <header class="convo-head">
            <button class="icon-round convo-back" id="convoBack" aria-label="Back to chat list" onclick="closeThread()">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m15 18-6-6 6-6"/></svg>
            </button>
            <div class="ravatar" id="convoAvatar" style="width:38px;height:38px;font-size:14px">E</div>
            <div class="convo-id">
              <div class="convo-title" id="convoTitle">Everyone</div>
              <div class="convo-sub" id="convoSub">Room on this network</div>
            </div>
            <div class="convo-actions">
              <button class="icon-round" id="btnVoice" aria-label="Start a voice call" title="Voice call" onclick="startCall(false)">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6.6 3.5 8.9 3l1.6 4-1.7 1.3a12 12 0 0 0 6 6L16 12.6l4 1.6-.5 2.3a2 2 0 0 1-2.2 1.6C10.2 17.4 5.9 13.1 5 6.7a2 2 0 0 1 1.6-2.2Z"/></svg>
              </button>
              <button class="icon-round" id="btnVideo" aria-label="Start a video call" title="Video call" onclick="startCall(true)">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="2.5" y="6" width="13" height="12" rx="3"/><path d="m15.5 12 6-3.5v11L15.5 16"/></svg>
              </button>
              <button class="icon-round" id="btnThreadInfo" aria-label="Conversation details" title="Details" onclick="toggleThreadInfo()">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 11v5"/><path d="M12 8h.01"/></svg>
              </button>
            </div>
          </header>

          <div class="convo-note" id="threadNote" role="note"></div>

          <div class="messages-wrap">
            <div class="messages" id="beamList" aria-live="polite" aria-label="Messages"></div>
            <button class="new-pill" id="newPill" aria-label="Scroll to new messages" onclick="jumpToBottom()">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14"/><path d="m6 13 6 6 6-6"/></svg>
              New messages
            </button>
          </div>
          <div class="typing-line" id="typingIndicator" aria-live="polite"></div>

          <div class="composer-wrap">
            <div class="emoji-pop" id="emojiPop" role="menu" aria-label="Emoji"></div>
            <div class="composer">
              <button class="icon-round" id="emojiBtn" aria-label="Insert emoji" aria-expanded="false" onclick="toggleEmoji()">
                <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M8.5 14.5a4.2 4.2 0 0 0 7 0"/><path d="M9 9.5h.01M15 9.5h.01"/></svg>
              </button>
              <button class="icon-round attach-btn" id="attachBtn" aria-label="Attach a file to this chat" onclick="pickChatFile()">
                <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20.5 11.5 12 20a5 5 0 0 1-7-7l8.5-8.6a3.4 3.4 0 0 1 4.8 4.8l-8.4 8.5a1.8 1.8 0 0 1-2.5-2.5l7.8-7.8"/></svg>
              </button>
              <textarea id="beamInput" placeholder="Message…" title="Enter sends, Shift+Enter adds a new line" rows="1" aria-label="Message text"></textarea>
              <button class="send-btn" aria-label="Send message" onclick="sendBeam()">
                <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m4 12 16-8-6 16-4-6z"/></svg>
              </button>
            </div>
          </div>
        </section>
      </main>

      <!-- ===== History view ===== -->
      <section class="view" id="viewHistory" aria-label="History">
        <div class="stage">
          <div class="stage-head">
            <div>
              <div class="stage-title">History</div>
              <div class="stage-sub" id="historySub">Everything this server has moved, newest first.</div>
            </div>
            <div class="spacer"></div>
            <div>
              <div class="field-label" style="margin-bottom:4px">Sort</div>
              <select class="select" id="historySort" aria-label="Sort history" onchange="renderHistory()">
                <option value="new">Newest first</option>
                <option value="old">Oldest first</option>
                <option value="big">Largest first</option>
              </select>
            </div>
            <div>
              <div class="field-label" style="margin-bottom:4px">Layout</div>
              <div class="seg" role="group" aria-label="History layout">
                <button id="histGridBtn" class="active" onclick="setHistoryLayout('grid')">Grid</button>
                <button id="histListBtn" onclick="setHistoryLayout('list')">List</button>
              </div>
            </div>
          </div>
          <div class="stage-body">
            <div class="file-list grid-view" id="historyList"></div>
          </div>
        </div>
      </section>

      <!-- ===== Details view ===== -->
      <section class="view" id="viewDetails" aria-label="Connection details">
        <div class="stage">
          <div class="stage-head">
            <div>
              <div class="stage-title">Details</div>
              <div class="stage-sub">Who is connected, what they sent and how this session is configured.</div>
            </div>
            <div class="spacer"></div>
            <button class="btn btn-quiet" onclick="fetchPeers()">Refresh</button>
          </div>
          <div class="stage-body">
            <div class="panel">
              <h3>This session</h3>
              <dl class="kv" id="sessionKv"></dl>
            </div>
            <div class="panel">
              <h3>Connections</h3>
              <div id="peersWrap"></div>
            </div>
            <div class="panel">
              <h3>Recent transfers</h3>
              <div id="transferWrap"></div>
            </div>
          </div>
        </div>
      </section>
    </div>

    <!-- Bottom tab bar (mobile only) -->
    <nav class="tabbar" role="tablist" aria-label="Sections">
      <button class="tab-btn" id="tabFiles" role="tab" aria-current="true" aria-label="Show files" onclick="setView('files')">
        <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.6a2 2 0 0 1-1.7-.9L9.6 4.2A2 2 0 0 0 7.9 3.3H4a2 2 0 0 0-2 2v12.7A2 2 0 0 0 4 20Z"/></svg>
        Files
      </button>
      <button class="tab-btn" id="tabChat" role="tab" aria-current="false" aria-label="Show chat" onclick="setView('chats')">
        <span class="tab-wrap">
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a8 8 0 0 1-8 8H5l-2 2V12a8 8 0 0 1 8-8h2a8 8 0 0 1 8 8z"/><path d="M9 11h6M9 14h4"/></svg>
          <span class="tab-dot" id="tabChatDot" aria-hidden="true">0</span>
        </span>
        Chats
      </button>
      <button class="tab-btn" id="tabHistory" role="tab" aria-current="false" aria-label="Show history" onclick="setView('history')">
        <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></svg>
        History
      </button>
      <button class="tab-btn" id="tabDetails" role="tab" aria-current="false" aria-label="Show connection details" onclick="setView('details')">
        <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3a14 14 0 0 1 0 18a14 14 0 0 1 0-18"/></svg>
        Details
      </button>
    </nav>
  </div><!-- /.app -->

  <!-- Call overlay -->
  <div id="callOverlay" class="overlay" style="display: none">
    <div class="modal call-card" role="dialog" aria-modal="true" aria-labelledby="callTitle">
      <div class="call-avatar" id="callAvatar">B</div>
      <div class="call-title" id="callTitle">Calling…</div>
      <div class="call-sub" id="callSub">Ringing on the other device</div>
      <video id="callPreview" class="call-video" autoplay muted playsinline></video>
      <div class="call-actions">
        <button class="call-btn" id="callMic" aria-label="Mute microphone" title="Mute" onclick="toggleMic()">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0"/><path d="M12 18v3"/></svg>
        </button>
        <button class="call-btn end" id="callEnd" aria-label="End call" title="End call" onclick="endCall()">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-17.6-17.6A2 2 0 0 1 4.2 2h3a2 2 0 0 1 2 1.7l.6 3a2 2 0 0 1-.6 1.9L7.6 9.9a16 16 0 0 0 6.5 6.5l1.5-1.5a2 2 0 0 1 1.9-.6l3 .6a2 2 0 0 1 1.5 2Z" transform="rotate(135 12 12)"/></svg>
        </button>
        <button class="call-btn" id="callCam" aria-label="Toggle camera" title="Camera" onclick="toggleCam()">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="2.5" y="6" width="13" height="12" rx="3"/><path d="m15.5 12 6-3.5v11L15.5 16"/></svg>
        </button>
      </div>
      <p class="call-note" id="callNote"></p>
    </div>
  </div>

  <input type="file" id="filePicker" multiple style="display: none" aria-hidden="true" onchange="handleFileSelect(this.files)" />
  <input type="file" id="folderPicker" webkitdirectory style="display: none" aria-hidden="true" onchange="handleFileSelect(this.files)" />
  <input type="file" id="chatFilePicker" multiple style="display: none" aria-hidden="true" onchange="handleChatFileSelect(this.files)" />
  <div id="toast" class="toast" role="status" aria-live="polite"></div>

  <!-- Preview lightbox: zoom, pan, rotate and browse for every file type -->
  <div class="lb" id="lightbox" role="dialog" aria-modal="true" aria-label="File preview">
    <div class="lb-head">
      <div class="lb-title" id="lbTitle">Preview</div>
      <div class="lb-sub" id="lbSub"></div>
      <div class="lb-tools">
        <button type="button" class="lb-btn" id="lbPrev" title="Previous file" aria-label="Previous file">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <button type="button" class="lb-btn" id="lbNext" title="Next file" aria-label="Next file">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m9 18 6-6-6-6"/></svg>
        </button>
        <button type="button" class="lb-btn" id="lbOut" title="Zoom out" aria-label="Zoom out">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M5 12h14"/></svg>
        </button>
        <button type="button" class="lb-btn lb-zoom" id="lbZoomPct" title="Reset zoom" aria-label="Reset zoom">100%</button>
        <button type="button" class="lb-btn" id="lbIn" title="Zoom in" aria-label="Zoom in">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>
        </button>
        <button type="button" class="lb-btn" id="lbFit" title="Fit to screen" aria-label="Fit to screen">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 9V5h4M20 9V5h-4M4 15v4h4M20 15v4h-4"/></svg>
        </button>
        <button type="button" class="lb-btn" id="lbRotate" title="Rotate 90 degrees" aria-label="Rotate 90 degrees">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a9 9 0 1 1-3-6.7"/><path d="M21 3v6h-6"/></svg>
        </button>
        <a class="lb-btn" id="lbDl" title="Download" aria-label="Download this file" rel="noopener">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12"/><path d="m7 11 5 5 5-5"/><path d="M4 20h16"/></svg>
        </a>
        <button type="button" class="lb-btn" id="lbClose" title="Close preview" aria-label="Close preview">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"/></svg>
        </button>
      </div>
    </div>
    <div class="lb-stage" id="lbStage"></div>
  </div>

  <script>
    var authToken = localStorage.getItem('pulse_token') || '';
    var REQUIRES_PIN = ${if (requiresPin) "true" else "false"};
    var MY_NAME = 'Browser';
    var myIp = '';
    var BOOT_TIME = Date.now();

    var allFiles = [];
    var lastMsgs = [];
    var typingOthers = [];
    var currentCat = 'all';
    var receivedOnly = false;
    var uploading = false;
    var transfersDone = 0;
    var currentXhr = null;
    var cancelRequested = false;
    var expandedFolders = {};

    var currentThread = '';
    var threadsSeen = {};
    var unread = {};
    var lastSeenTs = {};
    var chatInited = false;
    var prevLastId = '';
    var currentTab = 'files';

    var ICON = {
      doc: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/><path d="M9 13h6M9 17h4"/></svg>',
      image: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="1.6"/><path d="m5 18 5.5-5.5a2 2 0 0 1 2.8 0L21 18"/></svg>',
      video: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="m10 9 5 3-5 3z"/></svg>',
      audio: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 18V6l10-2v11"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="16.5" cy="16" r="2"/></svg>',
      archive: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="7" width="18" height="13" rx="2"/><path d="M3 11h18"/><path d="M11 7V4h2v3"/></svg>',
      code: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m8 8-4 4 4 4"/><path d="m16 8 4 4-4 4"/></svg>',
      pdf: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/><path d="M9 17v-4h1.5a1.5 1.5 0 0 1 0 3H9"/></svg>',
      apk: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3 4.5 7.5v9L12 21l7.5-4.5v-9z"/><path d="M12 8v8M8 12h8"/></svg>',
      download: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 4v12"/><path d="m7 11 5 5 5-5"/><path d="M4 20h16"/></svg>',
      trash: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h16"/><path d="M9 7V4h6v3"/><path d="M6 7l1 13h10l1-13"/></svg>',
      folder: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.6a2 2 0 0 1-1.7-.9L9.6 4.2A2 2 0 0 0 7.9 3.3H4a2 2 0 0 0-2 2v12.7A2 2 0 0 0 4 20Z"/></svg>',
      inbox: '<svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 12h-6l-2 3h-4l-2-3H2"/><path d="M5.5 5.1 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.9A2 2 0 0 0 16.7 4H7.3a2 2 0 0 0-1.8 1.1Z"/></svg>',
      chatbig: '<svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a8 8 0 0 1-8 8H5l-2 2V12a8 8 0 0 1 8-8h2a8 8 0 0 1 8 8z"/><path d="M9 11h6M9 14h4"/></svg>'
    };

    function el(id) { return document.getElementById(id); }

    function toast(msg) {
      var t = el('toast');
      t.textContent = msg;
      t.classList.add('show');
      clearTimeout(t._t);
      t._t = setTimeout(function () { t.classList.remove('show'); }, 2600);
    }

    function authHeaders() {
      var h = {};
      if (authToken) h['X-Pulse-Auth'] = authToken;
      return h;
    }

    function revealApp() { el('appRoot').style.display = 'flex'; }

    function lockOut() {
      localStorage.removeItem('pulse_token');
      authToken = '';
      el('appRoot').style.display = 'none';
      showAuth();
    }

    function showAuth() {
      el('authOverlay').style.display = 'flex';
      el('pinInput').value = '';
      el('pinError').style.display = 'none';
      el('pinInput').focus();
    }

    async function submitPin() {
      var pin = el('pinInput').value.trim();
      if (!pin) return;
      try {
        var res = await fetch('/api/auth', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ pin: pin })
        });
        var data = await res.json();
        if (data.success) {
          authToken = data.token || pin;
          localStorage.setItem('pulse_token', authToken);
          el('authOverlay').style.display = 'none';
          revealApp();
          fetchFiles();
          fetchInfo();
          fetchMessages();
        } else {
          var errEl = el('pinError');
          errEl.textContent = data.locked
            ? 'Too many wrong attempts. Try again in ' + Math.ceil(data.retryAfterSeconds || 60) + 's.'
            : 'Invalid PIN. Please check the phone screen.';
          errEl.style.display = 'block';
        }
      } catch (e) {
        el('pinError').style.display = 'block';
      }
    }

    function copyServerUrl() {
      copyToClipboard(location.origin + location.pathname);
    }

    /* ===== Categories ===== */
    var IMAGES = ['jpg','jpeg','png','webp','gif','heic','bmp','svg'];
    var VIDEOS = ['mp4','mkv','mov','webm','avi'];
    var AUDIO = ['mp3','wav','flac','m4a','ogg','aac'];
    var ARCHIVES = ['zip','rar','7z','tar','gz'];
    var CODES = ['json','js','ts','py','kt','html','css','md','sh','xml'];

    function extOf(name) { return name.indexOf('.') >= 0 ? name.split('.').pop().toLowerCase() : ''; }
    function fileKind(name) {
      var ext = extOf(name);
      if (IMAGES.indexOf(ext) >= 0) return 'images';
      if (VIDEOS.indexOf(ext) >= 0) return 'videos';
      if (AUDIO.indexOf(ext) >= 0) return 'audio';
      if (ARCHIVES.indexOf(ext) >= 0) return 'archives';
      return 'docs';
    }
    function iconMeta(name) {
      var ext = extOf(name);
      var kind = fileKind(name);
      if (kind === 'images') return { svg: ICON.image, tile: 'ic-blue' };
      if (kind === 'videos') return { svg: ICON.video, tile: 'ic-violet' };
      if (kind === 'audio') return { svg: ICON.audio, tile: 'ic-green' };
      if (kind === 'archives') return { svg: ICON.archive, tile: 'ic-amber' };
      if (ext === 'pdf') return { svg: ICON.pdf, tile: 'ic-amber' };
      if (ext === 'apk') return { svg: ICON.apk, tile: 'ic-green' };
      if (CODES.indexOf(ext) >= 0) return { svg: ICON.code, tile: 'ic-blue' };
      return { svg: ICON.doc, tile: 'ic-slate' };
    }
    function baseName(path) { return String(path).split('/').pop(); }
    function folderRootOf(name) {
      var s = String(name);
      var i = s.indexOf('/');
      return i > 0 ? s.substring(0, i) : '';
    }

    function formatBytes(bytes) {
      if (!bytes || bytes <= 0) return '0 B';
      var k = 1024;
      var sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
      var i = Math.floor(Math.log(bytes) / Math.log(k));
      return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i];
    }
    function clockOf(ts) {
      var d = new Date(ts);
      var h = d.getHours(), m = d.getMinutes();
      return (h < 10 ? '0' : '') + h + ':' + (m < 10 ? '0' : '') + m;
    }
    function dayLabelOf(ts) {
      var d = new Date(ts), n = new Date();
      var hm = clockOf(ts);
      if (d.toDateString() === n.toDateString()) return 'Today ' + hm;
      var y = new Date(n.getTime() - 86400000);
      if (d.toDateString() === y.toDateString()) return 'Yesterday ' + hm;
      var wd = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];
      var mo = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
      return wd[d.getDay()] + ' ' + d.getDate() + ' ' + mo[d.getMonth()] + ' ' + hm;
    }
    function uptimeText() {
      var s = Math.floor((Date.now() - BOOT_TIME) / 1000);
      if (s < 60) return s + 's';
      var m = Math.floor(s / 60);
      if (m < 60) return m + 'm ' + (s % 60) + 's';
      var h = Math.floor(m / 60);
      return h + 'h ' + (m % 60) + 'm';
    }

    /* ===== Tabs (mobile) ===== */
    var currentView = 'files';
    function setView(view) {
      currentView = view;
      var map = { files: 'viewFiles', chats: 'paneChat', history: 'viewHistory', details: 'viewDetails' };
      var tabs = { files: 'tabFiles', chats: 'tabChat', history: 'tabHistory', details: 'tabDetails' };
      var navs = { files: 'navFiles', chats: 'navChats', history: 'navHistory', details: 'navDetails' };
      Object.keys(map).forEach(function (k) {
        var node = el(map[k]);
        if (node) node.classList.toggle('active', k === view);
        var tb = el(tabs[k]);
        if (tb) tb.setAttribute('aria-current', k === view ? 'true' : 'false');
        var nb = el(navs[k]);
        if (nb) { nb.classList.toggle('active', k === view); nb.setAttribute('aria-current', k === view ? 'true' : 'false'); }
      });
      if (view === 'chats') {
        if (window.innerWidth < 960) el('paneChat').classList.remove('open-thread');
        renderThreadRail();
        renderChat(true);
      } else if (view === 'history') {
        renderHistory();
      } else if (view === 'details') {
        fetchPeers();
      }
    }
    function setTab(tab) { setView(tab === 'files' ? 'files' : 'chats'); }

    /* ===== Files ===== */
    async function fetchFiles() {
      try {
        var res = await fetch('/api/files', { headers: authHeaders() });
        if (res.status === 401 && REQUIRES_PIN) { lockOut(); return; }
        if (!res.ok) { setConnected(false); return; }
        allFiles = await res.json();
        setConnected(true);
        applyFilter();
        updateStats();
      } catch (e) { setConnected(false); }
    }

    async function fetchInfo() {
      try {
        var res = await fetch('/api/info');
        if (!res.ok) return;
        var info = await res.json();
        myIp = info.clientIp || '';
        el('statYourIp').textContent = myIp || '-';
        if (info.version) el('brandSub').textContent = 'Connected to ' + info.deviceName + ' - v' + info.version;
      } catch (e) {}
      el('statUptime').textContent = uptimeText();
    }

    function setConnected(ok) {
      var pill = el('connPill');
      el('connText').textContent = ok ? 'Connected' : 'Reconnecting…';
      pill.classList.toggle('off', !ok);
    }

    function updateStats() {
      var total = allFiles.reduce(function (s, f) { return s + (f.size || 0); }, 0);
      el('statFiles').textContent = String(allFiles.length);
      el('statSize').textContent = formatBytes(total);
      el('statTransfers').textContent = String(transfersDone);
      var newest = allFiles.reduce(function (m, f) { return Math.max(m, f.dateAdded || 0); }, 0);
      el('statSync').textContent = agoText(newest);
      el('statMessages').textContent = String(lastMsgs.length);
      el('statVisitors').textContent = String(peersList ? peersList.length : 0);
      el('statYourIp').textContent = myIp || '-';
      el('statUptime').textContent = uptimeText();
      if (currentView === 'details') renderSession();
    }

    function filteredFiles() {
      var q = el('searchInput').value.toLowerCase().trim();
      var list = allFiles;
      if (receivedOnly) list = list.filter(function (f) { return f.received; });
      if (currentCat !== 'all') {
        list = list.filter(function (f) { return fileKind(f.name) === currentCat; });
      }
      if (q) {
        list = list.filter(function (f) { return String(f.name).toLowerCase().indexOf(q) >= 0; });
      }
      return list;
    }

    function applyFilter() { renderFiles(filteredFiles()); }

    el('chips').addEventListener('click', function (e) {
      var chip = e.target.closest('.chip');
      if (!chip) return;
      document.querySelectorAll('#chips .chip').forEach(function (c) { c.classList.remove('active'); });
      chip.classList.add('active');
      currentCat = chip.dataset.cat;
      applyFilter();
    });

    el('receivedChip').addEventListener('click', function () {
      receivedOnly = !receivedOnly;
      this.classList.toggle('active', receivedOnly);
      this.setAttribute('aria-pressed', receivedOnly ? 'true' : 'false');
      applyFilter();
    });

    function renderEmpty(grid, message) {
      grid.textContent = '';
      var wrap = document.createElement('div');
      wrap.className = 'empty';
      var icon = document.createElement('div');
      icon.innerHTML = ICON.inbox;
      icon.setAttribute('aria-hidden', 'true');
      var text = document.createElement('div');
      text.textContent = message;
      wrap.appendChild(icon);
      wrap.appendChild(text);
      grid.appendChild(wrap);
    }

    function renderFiles(list) {
      var grid = el('filesGrid');
      grid.textContent = '';
      if (list.length === 0) {
        renderEmpty(grid, allFiles.length === 0
          ? 'No files yet. Drop files or folders above, or share them from the phone.'
          : 'Nothing matches this filter.');
        return;
      }
      var folders = {};
      var loose = [];
      list.forEach(function (f) {
        var root = folderRootOf(f.name);
        if (root) { (folders[root] = folders[root] || []).push(f); }
        else loose.push(f);
      });
      Object.keys(folders).sort().forEach(function (root) {
        grid.appendChild(buildFolderCard(root, folders[root]));
      });
      loose.forEach(function (f) { grid.appendChild(buildFileCard(f, list)); });
    }

    function downloadHref(id) {
      var href = '/download/' + encodeURIComponent(id);
      if (authToken) href += '?auth=' + encodeURIComponent(authToken);
      return href;
    }

    function buildFolderCard(root, files) {
      var card = document.createElement('div');
      card.className = 'card';

      var total = files.reduce(function (s, f) { return s + (f.size || 0); }, 0);

      var top = document.createElement('div');
      top.className = 'card-top';
      var tile = document.createElement('div');
      tile.className = 'tile ic-amber';
      tile.innerHTML = ICON.folder;
      var info = document.createElement('div');
      info.style.cssText = 'overflow: hidden; flex: 1; min-width: 0;';
      var nameEl = document.createElement('div');
      nameEl.className = 'file-name';
      nameEl.textContent = root;
      var subEl = document.createElement('div');
      subEl.className = 'file-meta';
      subEl.textContent = files.length + ' item' + (files.length === 1 ? '' : 's') + ' - ' + formatBytes(total);
      info.appendChild(nameEl);
      info.appendChild(subEl);
      top.appendChild(tile);
      top.appendChild(info);

      var contents = document.createElement('div');
      contents.className = 'folder-contents' + (expandedFolders[root] ? ' open' : '');
      files.slice(0, 300).forEach(function (f) {
        var row = document.createElement('div');
        row.className = 'f-row';
        var nm = document.createElement('span');
        nm.textContent = String(f.name).substring(root.length + 1);
        nm.title = f.name;
        var pv = document.createElement('button');
        pv.type = 'button';
        pv.textContent = 'preview';
        pv.addEventListener('click', function () { openPreview(previewSetOf(files), indexOfFile(files, f)); });
        var link = document.createElement('a');
        link.textContent = 'download';
        link.href = downloadHref(f.id);
        link.setAttribute('download', baseName(f.name));
        row.appendChild(nm);
        row.appendChild(pv);
        row.appendChild(link);
        contents.appendChild(row);
      });

      var toggle = document.createElement('button');
      toggle.className = 'folder-toggle';
      toggle.setAttribute('aria-label', 'Toggle contents of folder ' + root);
      toggle.textContent = expandedFolders[root] ? 'Hide files' : 'Show ' + files.length + ' files';
      toggle.addEventListener('click', function () {
        expandedFolders[root] = !expandedFolders[root];
        applyFilter();
      });

      var actions = document.createElement('div');
      actions.className = 'card-actions';
      var dl = document.createElement('a');
      dl.className = 'dl-btn';
      var href = '/download-folder?path=' + encodeURIComponent(root);
      if (authToken) href += '&auth=' + encodeURIComponent(authToken);
      dl.href = href;
      dl.setAttribute('download', root + '.zip');
      dl.innerHTML = ICON.download + '<span>Download folder</span>';
      actions.appendChild(dl);

      var del = document.createElement('button');
      del.className = 'icon-btn';
      del.title = 'Delete this folder from the device';
      del.setAttribute('aria-label', 'Delete folder ' + root);
      del.innerHTML = ICON.trash;
      del.addEventListener('click', function () {
        if (!window.confirm('Delete the whole "' + root + '" folder (' + files.length + ' files) from the phone?')) return;
        Promise.all(files.map(function (f) { return deleteFile(f, true); }))
          .then(function () { toast('Deleted ' + root); fetchFiles(); });
      });
      actions.appendChild(del);

      card.appendChild(top);
      card.appendChild(toggle);
      card.appendChild(contents);
      card.appendChild(actions);
      return card;
    }

    function buildFileCard(f, siblings) {
      var meta = iconMeta(f.name);
      var card = document.createElement('div');
      card.className = 'card';

      var top = document.createElement('div');
      top.className = 'card-top';

      var tile = document.createElement('div');
      tile.className = 'tile ' + meta.tile;
      tile.innerHTML = meta.svg;

      var info = document.createElement('div');
      info.style.cssText = 'overflow: hidden; flex: 1; min-width: 0;';
      var nameEl = document.createElement('div');
      nameEl.className = 'file-name';
      nameEl.textContent = baseName(f.name);
      nameEl.title = f.name;
      var subEl = document.createElement('div');
      subEl.className = 'file-meta';
      subEl.textContent = formatBytes(f.size) + (f.name.indexOf('/') >= 0 ? ' - ' + f.name.split('/').slice(0, -1).join('/') : '');
      info.appendChild(nameEl);
      info.appendChild(subEl);
      top.appendChild(tile);
      top.appendChild(info);

      var actions = document.createElement('div');
      actions.className = 'card-actions';

      var pv = document.createElement('button');
      pv.type = 'button';
      pv.className = 'icon-btn soft';
      pv.title = 'Preview ' + baseName(f.name);
      pv.setAttribute('aria-label', 'Preview ' + baseName(f.name));
      pv.innerHTML = ICON.image;
      pv.addEventListener('click', function () { openPreview(previewSetOf(siblings), indexOfFile(siblings, f)); });

      var dl = document.createElement('a');
      dl.className = 'dl-btn';
      dl.href = downloadHref(f.id);
      dl.setAttribute('download', baseName(f.name));
      dl.setAttribute('aria-label', 'Download ' + baseName(f.name));
      dl.innerHTML = ICON.download + '<span>Download</span>';

      var del = document.createElement('button');
      del.className = 'icon-btn';
      del.title = 'Delete from device';
      del.setAttribute('aria-label', 'Delete ' + baseName(f.name));
      del.innerHTML = ICON.trash;
      del.addEventListener('click', function () { deleteFile(f); });

      actions.appendChild(pv);
      actions.appendChild(dl);
      actions.appendChild(del);
      card.appendChild(top);
      card.appendChild(actions);
      return card;
    }

    /** Preview a hosted file, browsing sideways through the list it came from. */
    function previewSetOf(files) {
      return (files || []).map(function (item) {
        return {
          name: baseName(item.name), size: item.size,
          url: hostedPreviewUrl(item.id), dlUrl: downloadHref(item.id)
        };
      });
    }
    function indexOfFile(files, f) {
      var id = f.id;
      for (var i = 0; i < (files || []).length; i++) {
        if (files[i].id === id) return i;
      }
      return 0;
    }

    async function deleteFile(f, quiet) {
      if (!quiet && !window.confirm('Delete "' + baseName(f.name) + '" from the phone?')) return false;
      try {
        var headers = authHeaders();
        headers['Content-Type'] = 'application/json';
        var res = await fetch('/api/delete', {
          method: 'POST', headers: headers,
          body: JSON.stringify({ id: f.id })
        });
        var data = await res.json();
        if (data.success) {
          if (!quiet) { toast('Deleted ' + baseName(f.name)); fetchFiles(); }
          return true;
        }
        if (!quiet) toast(data.error || 'Delete failed');
        return false;
      } catch (e) {
        if (!quiet) toast('Delete failed');
        return false;
      }
    }

    /* ===== Uploads ===== */
    function pickFiles() { el('filePicker').click(); }
    function pickFolder() { el('folderPicker').click(); }

    var dropzone = el('dropzone');
    ['dragenter', 'dragover'].forEach(function (name) {
      dropzone.addEventListener(name, function (e) { e.preventDefault(); dropzone.classList.add('dragover'); });
    });
    ['dragleave', 'drop'].forEach(function (name) {
      dropzone.addEventListener(name, function (e) { e.preventDefault(); dropzone.classList.remove('dragover'); });
    });
    dropzone.addEventListener('drop', function (e) {
      var dt = e.dataTransfer;
      if (!dt) return;
      if (dt.items && dt.items.length && dt.items[0].webkitGetAsEntry) {
        collectDropEntries(dt).then(function (files) {
          if (files.length) handleFileSelect(files);
        });
      } else if (dt.files.length) {
        handleFileSelect(dt.files);
      }
    });
    dropzone.addEventListener('click', function () { pickFiles(); });
    dropzone.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pickFiles(); }
    });

    var outFiles = [];
    function collectDropEntries(dt) {
      outFiles = [];
      var jobs = [];
      for (var i = 0; i < dt.items.length; i++) {
        var item = dt.items[i];
        if (item.kind !== 'file') continue;
        var entry = item.webkitGetAsEntry();
        if (entry) jobs.push(walkEntry(entry, ''));
      }
      return Promise.all(jobs).then(function () { return outFiles; });
    }
    function walkEntry(entry, prefix) {
      if (entry.isFile) {
        return new Promise(function (resolve) {
          entry.file(function (file) {
            file._relPath = prefix + entry.name;
            outFiles.push(file);
            resolve();
          }, resolve);
        });
      }
      if (entry.isDirectory) {
        return new Promise(function (resolve) {
          var reader = entry.createReader();
          reader.readEntries(function (entries) {
            Promise.all(entries.map(function (e2) { return walkEntry(e2, prefix + entry.name + '/'); }))
              .then(resolve, resolve);
          }, resolve);
        });
      }
      return Promise.resolve();
    }

    function cancelUpload() {
      cancelRequested = true;
      if (currentXhr) { try { currentXhr.abort(); } catch (e) {} }
      toast('Cancelling upload…');
    }

    async function handleFileSelect(files) {
      if (!files || files.length === 0 || uploading) return;
      uploading = true;
      cancelRequested = false;
      updateStats();
      var arr = [];
      for (var i = 0; i < files.length; i++) {
        arr.push(files[i]);
      }
      var ok = 0, fail = 0;
      for (var j = 0; j < arr.length; j++) {
        if (cancelRequested) break;
        var success = await uploadSingleFile(arr[j]);
        if (success) ok++; else fail++;
      }
      if (cancelRequested) {
        toast('Upload cancelled - ' + ok + ' of ' + arr.length + ' sent');
      } else if (arr.length > 1) {
        toast('Uploaded ' + ok + ' file' + (ok === 1 ? '' : 's') + (fail ? ', ' + fail + ' failed' : ''));
      }
      transfersDone += ok;
      cancelRequested = false;
      uploading = false;
      currentXhr = null;
      el('filePicker').value = '';
      el('folderPicker').value = '';
      updateStats();
      fetchFiles();
    }

    function uploadSingleFile(file) {
      return new Promise(function (resolve) {
        var card = el('uploadCard');
        var bar = el('uploadBar');
        var pct = el('uploadPercent');
        var label = el('uploadLabel');
        var bytesTxt = el('uploadBytes');
        var speedTxt = el('uploadSpeed');

        card.style.display = 'flex';
        label.textContent = 'Uploading ' + (file._relPath || file.name);
        bar.style.width = '0%';
        bar.style.background = 'var(--accent)';
        pct.textContent = '0%';
        pct.style.color = 'var(--accent)';
        bytesTxt.textContent = '0 B / ' + formatBytes(file.size);
        speedTxt.textContent = '-';

        var xhr = new XMLHttpRequest();
        /* Browser uploads are never E2E-paired: X-Pair-Crypt is intentionally not set. */
        var url = '/upload?filename=' + encodeURIComponent(file.name);
        if (file._relPath) url += '&path=' + encodeURIComponent(file._relPath);
        var target = el('targetSelect') ? el('targetSelect').value : '';
        if (/^(\d{1,3}\.){3}\d{1,3}$/.test(target)) url += '&to=' + encodeURIComponent(target);
        if (authToken) url += (url.indexOf('?') >= 0 ? '&' : '?') + 'auth=' + encodeURIComponent(authToken);
        xhr.open('POST', url, true);
        if (authToken) xhr.setRequestHeader('X-Pulse-Auth', authToken);

        var lastTime = Date.now();
        var lastLoaded = 0;

        xhr.upload.onprogress = function (e) {
          if (!e.lengthComputable) return;
          var percent = Math.round((e.loaded / e.total) * 100);
          bar.style.width = percent + '%';
          pct.textContent = percent + '%';
          bytesTxt.textContent = formatBytes(e.loaded) + ' / ' + formatBytes(e.total);
          var now = Date.now();
          var dt = (now - lastTime) / 1000;
          if (dt >= 0.5) {
            speedTxt.textContent = formatBytes((e.loaded - lastLoaded) / dt) + '/s';
            lastTime = now;
            lastLoaded = e.loaded;
          }
        };

        xhr.onabort = function () {
          bar.style.background = 'var(--hairline)';
          pct.textContent = 'Cancelled';
          pct.style.color = 'var(--muted)';
          speedTxt.textContent = 'Cancelled';
          card.style.display = 'none';
          resolve(false);
        };

        xhr.onload = function () {
          currentXhr = null;
          if (xhr.status === 401) { lockOut(); resolve(false); return; }
          if (xhr.status >= 200 && xhr.status < 300) {
            bar.style.width = '100%';
            pct.textContent = '100%';
            speedTxt.textContent = 'Complete';
            setTimeout(function () { card.style.display = 'none'; }, 400);
            resolve(true);
          } else {
            bar.style.background = 'var(--error)';
            pct.textContent = 'Failed';
            pct.style.color = 'var(--error)';
            speedTxt.textContent = 'HTTP ' + xhr.status;
            setTimeout(function () { card.style.display = 'none'; }, 1200);
            resolve(false);
          }
        };

        xhr.onerror = function () {
          bar.style.background = 'var(--error)';
          pct.textContent = 'Failed';
          pct.style.color = 'var(--error)';
          speedTxt.textContent = 'Network error';
          setTimeout(function () { card.style.display = 'none'; }, 1200);
          resolve(false);
        };

        var formData = new FormData();
        formData.append('file', file, file.name);
        currentXhr = xhr;
        xhr.send(formData);
      });
    }

    /* ===== Chat with threads ===== */
    function copyToClipboard(text) {
      if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(function () { toast('Copied to clipboard'); });
        return;
      }
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.style.cssText = 'position: fixed; opacity: 0;';
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); toast('Copied to clipboard'); }
      catch (e) { toast('Copy failed'); }
      document.body.removeChild(ta);
    }

    function threadOf(m) { return m.threadId || ''; }

    function isMine(m) {
      if (m.sender !== MY_NAME) return false;
      if (!myIp) return true;
      return !m.clientIp || m.clientIp === myIp;
    }

    /* ===== Thread model ===== */
    var threadMeta = {};
    var infoOpen = false;
    var AV_GRADS = [
      'linear-gradient(135deg,#2563EB,#7C3AED)',
      'linear-gradient(135deg,#0EA5E9,#2563EB)',
      'linear-gradient(135deg,#F59E0B,#EF4444)',
      'linear-gradient(135deg,#10B981,#0EA5E9)',
      'linear-gradient(135deg,#8B5CF6,#EC4899)',
      'linear-gradient(135deg,#475569,#0F172A)'
    ];

    function hashOf(s) {
      var h = 0;
      for (var i = 0; i < s.length; i++) { h = (h * 31 + s.charCodeAt(i)) | 0; }
      return Math.abs(h);
    }
    function gradOf(seed) { return AV_GRADS[hashOf(String(seed)) % AV_GRADS.length]; }
    function initialsOf(name) {
      var parts = String(name || '?').trim().split(/[\s_-]+/).filter(Boolean);
      if (!parts.length) return '?';
      if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
      return (parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
    }
    function relTime(ts) {
      if (!ts) return '';
      var d = Date.now() - ts;
      if (d < 0) return 'now';
      if (d < 60000) return 'now';
      if (d < 3600000) return Math.floor(d / 60000) + 'm';
      if (d < 86400000) return Math.floor(d / 3600000) + 'h';
      if (d < 604800000) return Math.floor(d / 86400000) + 'd';
      return clockOf(ts);
    }

    function agoText(ts) {
      if (!ts) return '-';
      var r = relTime(ts);
      return r === 'now' ? 'just now' : r + ' ago';
    }

    function threadTitle(t) {
      if (!t) return 'Everyone';
      if (threadMeta[t] && threadMeta[t].title) return threadMeta[t].title;
      if (t.indexOf('ip:') === 0) {
        var ip = t.substring(3);
        for (var i = lastMsgs.length - 1; i >= 0; i--) {
          var m = lastMsgs[i];
          if (m.clientIp === ip && m.sender && m.sender !== MY_NAME) return String(m.sender);
        }
        return ip;
      }
      return t;
    }

    function threadHint(t) {
      if (!t) return 'Everyone on this network';
      var meta = threadMeta[t];
      if (meta && meta.kind === 'group') {
        var mem = (meta.members || []).join(', ');
        return (mem ? mem + ' · ' : '') + 'group chat';
      }
      if (t.indexOf('ip:') === 0) return 'Direct · ' + t.substring(3);
      return 'Conversation on this network';
    }

    function threadLabel(t) { return threadTitle(t); }

    function threadIds() {
      var seen = {};
      seen[''] = true;
      Object.keys(threadsSeen).forEach(function (t) { seen[t] = true; });
      Object.keys(threadMeta).forEach(function (t) { seen[t] = true; });
      seen[currentThread] = true;
      return Object.keys(seen).map(function (t) {
        var msgs = lastMsgs.filter(function (m) { return threadOf(m) === t; });
        var last = msgs.length ? msgs[msgs.length - 1] : null;
        return { id: t, title: threadTitle(t), last: last, ts: last ? (last.timestamp || 0) : 0, count: msgs.length };
      }).sort(function (a, b) {
        if (a.id === '') return -1;
        if (b.id === '') return 1;
        if (b.ts !== a.ts) return b.ts - a.ts;
        return a.title < b.title ? -1 : 1;
      });
    }

    async function fetchThreads() {
      try {
        var res = await fetch('/api/threads', { headers: authHeaders() });
        if (!res.ok) return;
        var list = await res.json();
        var next = {};
        list.forEach(function (x) { if (x && x.id) next[x.id] = x; });
        threadMeta = next;
        renderThreadRail();
      } catch (e) {}
    }

    async function fetchMessages() {
      try {
        var res = await fetch('/api/messages', { headers: authHeaders() });
        if (res.status === 401 && REQUIRES_PIN) { lockOut(); return; }
        if (!res.ok) { setConnected(false); return; }
        setConnected(true);
        var msgs = await res.json();
        processMessages(msgs);
      } catch (e) { setConnected(false); }
      fetchTyping();
    }

    function processMessages(msgs) {
      lastMsgs = msgs;

      /* Discover threads + per-thread unread bookkeeping. */
      msgs.forEach(function (m) {
        var t = threadOf(m);
        threadsSeen[t] = true;
        var ts = m.timestamp || 0;
        if (!chatInited) {
          lastSeenTs[t] = Math.max(lastSeenTs[t] || 0, ts);
          return;
        }
        if (ts > (lastSeenTs[t] || 0) && !isMine(m)) {
          if (t !== currentThread || !nearBottom()) {
            unread[t] = (unread[t] || 0) + 1;
          }
        }
        lastSeenTs[t] = Math.max(lastSeenTs[t] || 0, ts);
      });
      if (!chatInited) chatInited = true;

      updateStats();
      renderThreadRail();
      renderChat(false);
    }

    function unreadTotal() {
      var sum = 0;
      Object.keys(unread).forEach(function (t) { if (t !== currentThread) sum += unread[t]; });
      return sum;
    }

    function renderThreadRail() {
      var host = el('threadList');
      if (!host) return;
      var q = (el('railSearch').value || '').trim().toLowerCase();
      var rows = threadIds();
      if (q) rows = rows.filter(function (r) { return r.title.toLowerCase().indexOf(q) >= 0; });
      host.textContent = '';
      if (!rows.length) {
        var empty = document.createElement('div');
        empty.className = 'rail-empty';
        empty.textContent = q ? 'No chats match "' + q + '".' : 'No conversations yet. Say something in Everyone.';
        host.appendChild(empty);
      } else {
        rows.forEach(function (r) { host.appendChild(buildRailItem(r)); });
      }
      el('railCount').textContent = rows.length ? rows.length + '' : '';

      var total = unreadTotal();
      var dot = el('tabChatDot');
      if (dot) {
        dot.style.display = total > 0 ? 'inline-flex' : 'none';
        dot.textContent = total > 99 ? '99+' : String(total);
      }
      var navBadge = el('navChatsBadge');
      if (navBadge) {
        navBadge.style.display = total > 0 ? 'inline-flex' : 'none';
        navBadge.textContent = total > 99 ? '99+' : String(total);
      }
      renderConvoHead();
    }

    function buildRailItem(row) {
      var btn = document.createElement('button');
      btn.className = 'rail-item' + (row.id === currentThread ? ' active' : '');
      btn.type = 'button';
      btn.setAttribute('role', 'listitem');
      btn.setAttribute('aria-label', 'Open chat with ' + row.title);

      var av = document.createElement('div');
      av.className = 'ravatar';
      av.style.background = gradOf(row.id || 'everyone');
      av.textContent = initialsOf(row.title);
      av.setAttribute('aria-hidden', 'true');
      btn.appendChild(av);

      var body = document.createElement('div');
      body.className = 'rbody';
      var top = document.createElement('div');
      top.className = 'rtop';
      var name = document.createElement('div');
      name.className = 'rname';
      name.textContent = row.title;
      top.appendChild(name);
      var time = document.createElement('div');
      time.className = 'rtime';
      time.textContent = relTime(row.ts);
      top.appendChild(time);
      body.appendChild(top);

      var prev = document.createElement('div');
      prev.className = 'rprev';
      if (row.last) {
        var who = document.createElement('b');
        who.textContent = (row.last.sender === MY_NAME ? 'You' : String(row.last.sender || '')) + ': ';
        prev.appendChild(who);
        var snippet = String(row.last.text || '');
        if (snippet === '' && row.last.attachment) snippet = 'File: ' + row.last.attachment.name;
        prev.appendChild(document.createTextNode(snippet));
      } else {
        prev.textContent = threadHint(row.id);
      }
      body.appendChild(prev);
      btn.appendChild(body);

      var n = row.id === currentThread ? 0 : (unread[row.id] || 0);
      if (n > 0) {
        var badge = document.createElement('span');
        badge.className = 'rbadge';
        badge.textContent = n > 99 ? '99+' : String(n);
        btn.appendChild(badge);
      }
      btn.addEventListener('click', function () { selectThread(row.id); });
      return btn;
    }

    function renderConvoHead() {
      var t = currentThread;
      var av = el('convoAvatar');
      av.textContent = initialsOf(threadTitle(t));
      av.style.background = gradOf(t || 'everyone');
      el('convoTitle').textContent = threadTitle(t);
      el('convoSub').textContent = threadHint(t);
      var note = el('threadNote');
      if (infoOpen) {
        note.classList.add('show');
        note.textContent = t
          ? 'Direct conversation on this Wi-Fi network. Traffic stays local; files you attach land in Hosted files.'
          : 'Everyone on this network can read and write this room. Pair two PulseDrop apps for end-to-end encrypted chats.';
      } else {
        note.classList.remove('show');
        note.textContent = '';
      }
    }

    function selectThread(t) {
      currentThread = t;
      unread[t] = 0;
      el('paneChat').classList.add('open-thread');
      renderThreadRail();
      renderChat(true);
      if (window.innerWidth >= 960) { try { el('beamInput').focus(); } catch (e) {} }
    }

    function closeThread() {
      el('paneChat').classList.remove('open-thread');
      renderThreadRail();
    }

    function toggleThreadInfo() { infoOpen = !infoOpen; renderConvoHead(); }

    function toggleNewChatMenu() {
      var n = el('railNew');
      var open = n.classList.toggle('open');
      if (open) el('newIpInput').focus();
    }

    function startIpThread() {
      var v = (el('newIpInput').value || '').trim();
      if (!/^(\d{1,3}\.){3}\d{1,3}$/.test(v)) { toast('Enter a full IPv4 address, e.g. 192.168.1.24'); return; }
      el('newIpInput').value = '';
      el('railNew').classList.remove('open');
      threadsSeen['ip:' + v] = true;
      selectThread('ip:' + v);
    }

    function nearBottom() {
      var list = el('beamList');
      return list.scrollHeight - list.scrollTop - list.clientHeight < 90;
    }

    function jumpToBottom() {
      var list = el('beamList');
      list.scrollTop = list.scrollHeight;
      el('newPill').style.display = 'none';
    }

    function currentThreadMsgs() {
      var list = lastMsgs.filter(function (m) { return threadOf(m) === currentThread; });
      return list.slice(-200);
    }

    function renderChat(forceScroll) {
      var list = el('beamList');
      var disp = currentThreadMsgs();
      var lastId = disp.length ? String(disp[disp.length - 1].id || '') : '';
      var fp = disp.length + '|' + lastId + '|' + currentThread;
      if (!forceScroll && fp === list._fp) return;
      var stuck = nearBottom();
      list._fp = fp;
      list.textContent = '';

      if (disp.length === 0) {
        var empty = document.createElement('div');
        empty.className = 'chat-empty';
        var icon = document.createElement('div');
        icon.innerHTML = ICON.chatbig;
        icon.setAttribute('aria-hidden', 'true');
        var line = document.createElement('div');
        line.textContent = currentThread
          ? 'No messages in this thread yet. Write one to start the conversation.'
          : 'No messages yet. Say something to ' + el('topbarDevice').textContent + ' or other browsers.';
        empty.appendChild(icon);
        empty.appendChild(line);
        list.appendChild(empty);
      } else {
        var prev = null;
        disp.forEach(function (m, idx) {
          var ts = m.timestamp || 0;
          if (!prev || (ts - (prev.timestamp || 0)) > 1800000) {
            var sep = document.createElement('div');
            sep.className = 'day-sep';
            sep.textContent = dayLabelOf(ts);
            list.appendChild(sep);
          }
          var nextIn = idx < disp.length - 1 ? disp[idx + 1] : null;
          var groupedNext = nextIn && nextIn.sender === m.sender &&
            ((nextIn.timestamp || 0) - ts) <= 120000 && (nextIn.timestamp || 0) >= ts;
          var groupedPrev = prev && prev.sender === m.sender &&
            (ts - (prev.timestamp || 0)) <= 120000 && ts >= (prev.timestamp || 0);
          list.appendChild(buildBubble(m, !!groupedPrev, !groupedNext));
          prev = m;
        });
      }

      if (forceScroll || stuck) {
        list.scrollTop = list.scrollHeight;
        el('newPill').style.display = 'none';
      } else if (lastId && lastId !== prevLastId) {
        el('newPill').style.display = 'inline-flex';
      }
      prevLastId = lastId;
    }

    function buildBubble(m, groupedPrev, isLatest) {
      var mine = isMine(m);
      var row = document.createElement('div');
      row.className = 'bubble-row ' + (mine ? 'mine' : 'in') + (groupedPrev ? ' group-cont' : '');

      var sender = String(m.sender || 'Guest');
      if (!mine) {
        var av = document.createElement('div');
        av.className = 'avatar' + (isLatest ? '' : ' hidden');
        av.textContent = sender.charAt(0).toUpperCase();
        av.setAttribute('aria-hidden', 'true');
        row.appendChild(av);
      }

      var col = document.createElement('div');
      col.className = 'bubble-col';

      if (m.attachment) col.appendChild(buildAttachmentCard(m.attachment));

      if (String(m.text || '') !== '') {
        var bub = document.createElement('div');
        bub.className = 'bubble';
        bub.textContent = m.text;
        col.appendChild(bub);
      }

      var meta = document.createElement('div');
      meta.className = 'bubble-meta';
      meta.style.minHeight = isLatest ? '' : '0';
      meta.style.display = 'flex';
      if (isLatest) {
        var who = document.createElement('span');
        who.className = 'meta-name';
        who.textContent = mine ? 'You' : sender;
        meta.appendChild(who);
        var tm = document.createElement('span');
        tm.textContent = clockOf(m.timestamp || Date.now());
        meta.appendChild(tm);
        var cp = document.createElement('button');
        cp.className = 'bubble-copy';
        cp.textContent = 'copy';
        cp.setAttribute('aria-label', 'Copy message');
        cp.addEventListener('click', function () { copyToClipboard(String(m.text || m.attachment.name || '')); });
        meta.appendChild(cp);
      } else {
        meta.style.visibility = 'hidden';
        meta.textContent = '.';
      }
      col.appendChild(meta);

      row.appendChild(col);
      return row;
    }

    /* ===== Chat attachments: the file itself lives in the bubble ===== */
    function withAuth(url) {
      if (!authToken) return url;
      return url + (url.indexOf('?') >= 0 ? '&' : '?') + 'auth=' + encodeURIComponent(authToken);
    }
    function chatFileUrl(id, asDownload) {
      return withAuth('/chatfile/' + encodeURIComponent(id) + (asDownload ? '?dl=1' : ''));
    }
    function hostedPreviewUrl(id) { return withAuth('/preview/' + encodeURIComponent(id)); }

    function kindLabel(name) {
      var kind = fileKind(name);
      if (kind === 'images') return 'IMAGE';
      if (kind === 'videos') return 'VIDEO';
      if (kind === 'audio') return 'AUDIO';
      if (kind === 'archives') return 'ARCHIVE';
      var ext = extOf(name);
      return ext ? ext.toUpperCase() : 'FILE';
    }

    function buildAttachmentCard(att) {
      var card = document.createElement('div');
      card.className = 'att-card';

      var meta = iconMeta(att.name);
      var thumb = document.createElement('div');
      thumb.className = 'tile ' + meta.tile + ' att-thumb';
      thumb.setAttribute('aria-hidden', 'true');
      if (fileKind(att.name) === 'images') {
        var im = document.createElement('img');
        im.src = chatFileUrl(att.id, false);
        im.alt = '';
        im.addEventListener('error', function () { thumb.innerHTML = meta.svg; });
        thumb.appendChild(im);
      } else {
        thumb.innerHTML = meta.svg;
      }

      var body = document.createElement('div');
      body.className = 'att-body';
      var nm = document.createElement('div');
      nm.className = 'att-name';
      nm.textContent = att.name;
      nm.title = att.name;
      var mm = document.createElement('div');
      mm.className = 'att-meta';
      mm.textContent = formatBytes(att.size) + ' · ' + kindLabel(att.name);
      body.appendChild(nm);
      body.appendChild(mm);

      var acts = document.createElement('div');
      acts.className = 'att-acts';
      var pv = document.createElement('button');
      pv.type = 'button';
      pv.className = 'att-btn prim';
      pv.textContent = 'Preview';
      pv.setAttribute('aria-label', 'Preview ' + att.name);
      pv.addEventListener('click', function () { openThreadPreview(att.id); });
      var dl = document.createElement('a');
      dl.className = 'att-btn';
      dl.textContent = 'Save';
      dl.href = chatFileUrl(att.id, true);
      dl.setAttribute('download', att.name);
      dl.setAttribute('aria-label', 'Download ' + att.name);
      acts.appendChild(pv);
      acts.appendChild(dl);

      thumb.addEventListener('click', function () { openThreadPreview(att.id); });
      card.appendChild(thumb);
      card.appendChild(body);
      card.appendChild(acts);
      return card;
    }

    function openThreadPreview(attId) {
      var items = [];
      currentThreadMsgs().forEach(function (m) {
        var a = m.attachment;
        if (!a) return;
        items.push({ name: a.name, size: a.size, url: chatFileUrl(a.id, false), dlUrl: chatFileUrl(a.id, true) });
      });
      var idx = 0;
      items.forEach(function (it, i) {
        if (it.url.indexOf(encodeURIComponent(attId)) >= 0) idx = i;
      });
      openPreview(items, idx);
    }

    /* ===== Preview lightbox ===== */
    var TEXTY = ['txt','md','json','js','ts','py','kt','java','html','css','sh','xml','csv','log','yml','yaml','ini','conf'];
    var lb = { items: [], idx: 0, scale: 1, rot: 0, tx: 0, ty: 0, zoomable: false, node: null };

    function applyLbTransform() {
      if (!lb.node) return;
      lb.node.style.transform = 'translate(' + lb.tx + 'px,' + lb.ty + 'px) scale(' + lb.scale + ') rotate(' + lb.rot + 'deg)';
      el('lbZoomPct').textContent = Math.round(lb.scale * 100) + '%';
    }
    function lbZoom(factor) {
      if (!lb.zoomable) return;
      lb.scale = Math.min(16, Math.max(0.2, lb.scale * factor));
      if (Math.abs(lb.scale - 1) < 0.001) { lb.scale = 1; lb.tx = 0; lb.ty = 0; }
      applyLbTransform();
    }
    function lbReset() {
      lb.scale = 1; lb.rot = 0; lb.tx = 0; lb.ty = 0;
      applyLbTransform();
    }
    function lbRotate() {
      if (!lb.zoomable) return;
      lb.rot = (lb.rot + 90) % 360;
      applyLbTransform();
    }
    function setZoomUi() {
      ['lbIn', 'lbOut', 'lbZoomPct', 'lbFit', 'lbRotate'].forEach(function (id) {
        el(id).disabled = !lb.zoomable;
      });
      el('lbZoomPct').textContent = Math.round(lb.scale * 100) + '%';
    }

    function openPreview(items, index) {
      var list = (items || []).filter(Boolean);
      if (!list.length) return;
      lb.items = list;
      el('lightbox').classList.add('open');
      document.body.classList.add('lb-block');
      showLbItem(index || 0);
      el('lbClose').focus();
    }
    function closePreview() {
      el('lightbox').classList.remove('open');
      document.body.classList.remove('lb-block');
      var stage = el('lbStage');
      stage.textContent = '';
      lb.node = null;
      lb.items = [];
    }

    function lbShell(cls, tag) {
      var node = document.createElement(tag || 'div');
      node.className = 'lb-media ' + cls;
      lb.node = node;
      return node;
    }

    function showLbFallback(it, reason) {
      var stage = el('lbStage');
      stage.textContent = '';
      lb.zoomable = false;
      lb.node = null;
      var box = document.createElement('div');
      box.className = 'lb-none';
      var tile = document.createElement('div');
      tile.className = 'tile ic-slate';
      tile.innerHTML = iconMeta(it.name).svg;
      tile.style.margin = '0 auto 12px';
      var nm = document.createElement('div');
      nm.className = 'nm';
      nm.textContent = it.name;
      var mm = document.createElement('div');
      mm.className = 'mm';
      mm.textContent = formatBytes(it.size) + ' · ' + kindLabel(it.name) + (reason ? ' · ' + reason : '');
      var btn = document.createElement('a');
      btn.className = 'att-btn prim';
      btn.textContent = 'Download to view';
      btn.href = it.dlUrl;
      btn.setAttribute('download', it.name);
      box.appendChild(tile);
      box.appendChild(nm);
      box.appendChild(mm);
      box.appendChild(btn);
      stage.appendChild(box);
      setZoomUi();
    }

    function showLbItem(index) {
      if (!lb.items.length) return;
      var count = lb.items.length;
      var idx = ((index % count) + count) % count;
      var it = lb.items[idx];
      lb.idx = idx;
      lb.scale = 1; lb.rot = 0; lb.tx = 0; lb.ty = 0; lb.node = null; lb.zoomable = false;

      el('lbTitle').textContent = it.name;
      el('lbTitle').title = it.name;
      el('lbSub').textContent = formatBytes(it.size) + '  ·  ' + (idx + 1) + ' of ' + count;
      el('lbDl').href = it.dlUrl;
      el('lbDl').setAttribute('download', it.name);
      el('lbPrev').disabled = count < 2;
      el('lbNext').disabled = count < 2;

      var stage = el('lbStage');
      stage.textContent = '';
      var ext = extOf(it.name);
      var kind = fileKind(it.name);

      if (kind === 'images') {
        lb.zoomable = true;
        var img = lbShell('lb-img pan', 'img');
        img.alt = it.name;
        img.src = it.url;
        img.addEventListener('error', function () { showLbFallback(it, 'preview failed'); });
        stage.appendChild(img);
      } else if (kind === 'videos') {
        lb.zoomable = true;
        var vid = lbShell('lb-video', 'video');
        vid.src = it.url;
        vid.controls = true;
        vid.setAttribute('playsinline', '');
        vid.preload = 'metadata';
        vid.addEventListener('error', function () { showLbFallback(it, 'this browser cannot play it'); });
        stage.appendChild(vid);
      } else if (kind === 'audio') {
        var wrap = document.createElement('div');
        wrap.className = 'lb-audio-wrap';
        var disc = document.createElement('div');
        disc.className = 'tile ic-green';
        disc.innerHTML = ICON.audio;
        disc.style.cssText = 'width:64px;height:64px;border-radius:20px;';
        var au = document.createElement('audio');
        au.className = 'lb-audio';
        au.src = it.url;
        au.controls = true;
        var cap = document.createElement('div');
        cap.className = 'lb-sub';
        cap.textContent = it.name;
        cap.style.maxWidth = '86vw';
        cap.style.overflow = 'hidden';
        cap.style.textOverflow = 'ellipsis';
        cap.style.whiteSpace = 'nowrap';
        wrap.appendChild(disc);
        wrap.appendChild(cap);
        wrap.appendChild(au);
        stage.appendChild(wrap);
      } else if (ext === 'pdf') {
        lb.zoomable = true;
        var frame = lbShell('lb-pdf', 'iframe');
        frame.src = it.url;
        frame.title = it.name;
        stage.appendChild(frame);
      } else if (TEXTY.indexOf(ext) >= 0) {
        lb.zoomable = true;
        var scroller = lbShell('lb-text');
        var pre = document.createElement('pre');
        pre.textContent = 'Loading…';
        scroller.appendChild(pre);
        stage.appendChild(scroller);
        fetch(it.url, { headers: authHeaders() })
          .then(function (res) { if (!res.ok) throw new Error('http'); return res.text(); })
          .then(function (body) {
            if (lb.node !== scroller) return;
            pre.textContent = body.length > 400000 ? body.slice(0, 400000) + '\n\n… truncated. Download the file to read all of it.' : body;
          })
          .catch(function () { if (lb.node === scroller) showLbFallback(it, 'could not read it'); });
      } else {
        showLbFallback(it, 'no inline preview for this type');
        setZoomUi();
        return;
      }
      setZoomUi();
      applyLbTransform();
    }

    el('lbClose').addEventListener('click', closePreview);
    el('lbPrev').addEventListener('click', function () { showLbItem(lb.idx - 1); });
    el('lbNext').addEventListener('click', function () { showLbItem(lb.idx + 1); });
    el('lbIn').addEventListener('click', function () { lbZoom(1.25); });
    el('lbOut').addEventListener('click', function () { lbZoom(1 / 1.25); });
    el('lbZoomPct').addEventListener('click', lbReset);
    el('lbFit').addEventListener('click', lbReset);
    el('lbRotate').addEventListener('click', lbRotate);

    var lbDrag = null;
    el('lbStage').addEventListener('pointerdown', function (e) {
      if (!lb.zoomable || lb.scale <= 1) return;
      lbDrag = { x: e.clientX, y: e.clientY, tx: lb.tx, ty: lb.ty };
      if (lb.node) lb.node.classList.add('dragging');
      e.preventDefault();
    });
    document.addEventListener('pointermove', function (e) {
      if (!lbDrag) return;
      lb.tx = lbDrag.tx + (e.clientX - lbDrag.x);
      lb.ty = lbDrag.ty + (e.clientY - lbDrag.y);
      applyLbTransform();
    });
    ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (evt) {
      document.addEventListener(evt, function () {
        if (!lbDrag) return;
        lbDrag = null;
        if (lb.node) lb.node.classList.remove('dragging');
      });
    });
    el('lbStage').addEventListener('dblclick', function () {
      if (!lb.zoomable) return;
      if (lb.scale === 1) lbZoom(2.5); else lbReset();
    });
    el('lbStage').addEventListener('wheel', function (e) {
      if (!lb.zoomable) return;
      e.preventDefault();
      lbZoom(e.deltaY < 0 ? 1.15 : 1 / 1.15);
    }, { passive: false });

    document.addEventListener('keydown', function (e) {
      if (!el('lightbox').classList.contains('open')) return;
      var tag = (e.target && e.target.tagName) || '';
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      if (e.key === 'Escape') { closePreview(); return; }
      if (e.key === 'ArrowLeft') { showLbItem(lb.idx - 1); return; }
      if (e.key === 'ArrowRight') { showLbItem(lb.idx + 1); return; }
      if (e.key === '+' || e.key === '=') { lbZoom(1.25); return; }
      if (e.key === '-' || e.key === '_') { lbZoom(1 / 1.25); return; }
      if (e.key === '0' || e.key === 'f' || e.key === 'F') { lbReset(); return; }
      if (e.key === 'r' || e.key === 'R') { lbRotate(); return; }
    });

    async function fetchTyping() {
      try {
        var res = await fetch('/api/typing');
        if (!res.ok) return;
        var senders = await res.json();
        typingOthers = senders.filter(function (s) { return s !== MY_NAME; });
      } catch (e) { typingOthers = []; }
      renderTyping();
    }

    function renderTyping() {
      var line = el('typingIndicator');
      line.textContent = '';
      if (!typingOthers.length) return;
      var names = document.createElement('span');
      names.textContent = typingOthers.join(', ') + ' is typing';
      line.appendChild(names);
      var dots = document.createElement('span');
      dots.className = 'typing-dots';
      dots.setAttribute('aria-hidden', 'true');
      for (var i = 0; i < 3; i++) dots.appendChild(document.createElement('i'));
      line.appendChild(dots);
    }

    var lastTypingPing = 0;
    el('beamInput').addEventListener('input', function () {
      var now = Date.now();
      if (now - lastTypingPing > 2000) {
        lastTypingPing = now;
        fetch('/api/typing', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ sender: MY_NAME, typing: true })
        }).catch(function () {});
      }
    });

    function clearTyping() {
      fetch('/api/typing', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sender: MY_NAME, typing: false })
      }).catch(function () {});
    }

    function postThreadMessage(text, attachmentId) {
      var headers = authHeaders();
      headers['Content-Type'] = 'application/json';
      var body = { text: text || '', sender: MY_NAME };
      if (attachmentId) body.attachmentId = attachmentId;
      if (currentThread) body.thread = currentThread;
      return fetch('/api/messages', { method: 'POST', headers: headers, body: JSON.stringify(body) })
        .then(function (res) {
          if (res.status === 401) { lockOut(); return false; }
          return res.ok;
        })
        .catch(function () { return false; });
    }

    async function sendBeam() {
      var input = el('beamInput');
      var text = input.value.trim();
      if (!text) return;
      var ok = await postThreadMessage(text);
      if (ok) {
        input.value = '';
        input.style.height = 'auto';
        closeEmoji();
        clearTyping();
        unread[currentThread] = 0;
        fetchMessages();
      } else {
        toast('Send failed');
      }
    }

    /* ===== Emoji tray ===== */
    function browserHasColorEmoji() {
      try {
        var size = 30;
        var canvas = document.createElement('canvas');
        canvas.width = size;
        canvas.height = size;
        var ctx = canvas.getContext('2d');
        if (!ctx) return true;
        ctx.textBaseline = 'top';
        ctx.font = size + 'px sans-serif';
        ctx.fillText(String.fromCharCode(55357, 56835), 0, 0);
        var data = ctx.getImageData(0, 0, size, size).data;
        var ink = 0, chroma = 0;
        for (var i = 0; i < data.length; i += 4) {
          if (data[i + 3] < 12) continue;
          ink++;
          var r = data[i], g = data[i + 1], b = data[i + 2];
          if (Math.abs(r - g) > 14 || Math.abs(g - b) > 14 || Math.abs(r - b) > 14) chroma++;
        }
        return ink > 40 && chroma > 20;
      } catch (e) { return true; }
    }

    var EMOJIS = ['😀', '😂', '🤣', '😍', '🤩', '😎', '🥳', '😅', '🙂', '😉', '😴', '🤔', '🫡', '😭', '👍', '👎', '👏', '🙏', '🔥', '✨', '🎉', '💯', '❤️', '💙', '🛤', '📁', '📂', '📸', '🎬', '🎧', '📞', '📝', '✅', '⚠️', '❌', '💡', '🔒', '🚀', '🎯', '⏰'];
    var emojiBuilt = false;
    function toggleEmoji() {
      var pop = el('emojiPop');
      if (pop.classList.contains('open')) { closeEmoji(); return; }
      if (!emojiBuilt) {
        EMOJIS.forEach(function (e) {
          var b = document.createElement('button');
          b.type = 'button';
          b.textContent = e;
          b.setAttribute('aria-label', 'Insert ' + e);
          b.addEventListener('click', function () {
            var input = el('beamInput');
            input.value += e;
            input.focus();
          });
          pop.appendChild(b);
        });
        emojiBuilt = true;
      }
      pop.classList.add('open');
      el('emojiBtn').setAttribute('aria-expanded', 'true');
    }
    function closeEmoji() {
      el('emojiPop').classList.remove('open');
      el('emojiBtn').setAttribute('aria-expanded', 'false');
    }
    document.addEventListener('click', function (e) {
      var pop = el('emojiPop');
      if (!pop || !pop.classList.contains('open')) return;
      if (pop.contains(e.target)) return;
      var btn = el('emojiBtn');
      if (btn && btn.contains(e.target)) return;
      closeEmoji();
    });

    /* ===== Attach a file to the current thread ===== */
    function pickChatFile() { el('chatFilePicker').click(); }

    function uploadChatFile(file) {
      return new Promise(function (resolve) {
        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/chatupload?filename=' + encodeURIComponent(file.name) +
          (authToken ? '&auth=' + encodeURIComponent(authToken) : ''), true);
        if (authToken) xhr.setRequestHeader('X-Pulse-Auth', authToken);
        xhr.onload = function () {
          var att = null;
          try {
            var out = JSON.parse(xhr.responseText);
            if (out && out.success && out.attachment) att = out.attachment;
          } catch (e) {}
          resolve(att);
        };
        xhr.onerror = function () { resolve(null); };
        xhr.onabort = function () { resolve(null); };
        var fd = new FormData();
        fd.append('file', file, file.name);
        xhr.send(fd);
      });
    }

    async function handleChatFileSelect(files) {
      var list = Array.prototype.slice.call(files || []);
      el('chatFilePicker').value = '';
      if (!list.length) return;
      el('attachBtn').classList.add('is-busy');
      var caption = el('beamInput').value.trim();
      var sent = 0;
      for (var i = 0; i < list.length; i++) {
        var att = await uploadChatFile(list[i]);
        if (!att) continue;
        if (await postThreadMessage(i === 0 ? caption : '', att.id)) sent++;
      }
      el('attachBtn').classList.remove('is-busy');
      if (sent) {
        el('beamInput').value = '';
        el('beamInput').style.height = 'auto';
        unread[currentThread] = 0;
        closeEmoji();
        clearTyping();
        fetchMessages();
      }
      toast(sent
        ? sent + ' file' + (sent > 1 ? 's' : '') + ' shared in ' + threadTitle(currentThread)
        : 'Upload failed');
    }

    el('beamInput').addEventListener('keydown', function (e) {
      if (e.key !== 'Enter') return;
      if (e.shiftKey) return;
      e.preventDefault();
      sendBeam();
    });

    /* ===== Layout, upload target, history, details ===== */
    var fileLayout = 'grid';
    var historyLayout = 'grid';
    var peersList = [];

    function setLayout(mode) {
      fileLayout = mode;
      el('filesGrid').className = 'file-list ' + (mode === 'grid' ? 'grid-view' : 'list-view');
      el('layoutGrid').classList.toggle('active', mode === 'grid');
      el('layoutList').classList.toggle('active', mode !== 'grid');
      renderFiles(filteredFiles());
    }

    function setHistoryLayout(mode) {
      historyLayout = mode;
      el('historyList').className = 'file-list ' + (mode === 'grid' ? 'grid-view' : 'list-view');
      el('histGridBtn').classList.toggle('active', mode === 'grid');
      el('histListBtn').classList.toggle('active', mode !== 'grid');
      renderHistory();
    }

    function saveTarget() {
      var t = el('targetSelect').value;
      toast(t ? 'Uploads now go only to ' + t : 'Uploads are visible to everyone again');
    }

    function updateTargetOptions() {
      var sel = el('targetSelect');
      if (!sel) return;
      var cur = sel.value;
      sel.textContent = '';
      var all = document.createElement('option');
      all.value = '';
      all.textContent = 'Everyone on the network';
      sel.appendChild(all);
      peersList.forEach(function (p) {
        if (!p.ip || p.ip === myIp) return;
        var o = document.createElement('option');
        o.value = p.ip;
        o.textContent = p.name + '  ·  ' + p.ip;
        sel.appendChild(o);
      });
      if (cur) sel.value = cur;
    }

    function renderHistory() {
      var host = el('historyList');
      if (!host) return;
      var sort = el('historySort').value;
      var list = allFiles.slice();
      if (sort === 'old') list.sort(function (a, b) { return (a.dateAdded || 0) - (b.dateAdded || 0); });
      else if (sort === 'big') list.sort(function (a, b) { return (b.size || 0) - (a.size || 0); });
      else list.sort(function (a, b) { return (b.dateAdded || 0) - (a.dateAdded || 0); });
      var bytes = list.reduce(function (s, f) { return s + (f.size || 0); }, 0);
      el('historySub').textContent = list.length
        ? list.length + ' file' + (list.length === 1 ? '' : 's') + ' · ' + formatBytes(bytes) + ' hosted on this phone'
        : 'Nothing has moved through this server yet.';
      if (!list.length) { renderEmpty(host, 'No files yet. Upload from the Files tab, or share from the phone.'); return; }
      host.textContent = '';
      list.forEach(function (f) { host.appendChild(buildFileCard(f, list)); });
    }

    async function fetchPeers() {
      try {
        var res = await fetch('/api/peers', { headers: authHeaders() });
        if (res.status === 401 && REQUIRES_PIN) { lockOut(); return; }
        if (!res.ok) return;
        peersList = await res.json();
      } catch (e) { peersList = []; }
      updateTargetOptions();
      renderPeers();
      renderSession();
    }

    function renderPeers() {
      var wrap = el('peersWrap');
      if (!wrap) return;
      wrap.textContent = '';
      if (!peersList.length) {
        var none = document.createElement('div');
        none.className = 'rail-empty';
        none.textContent = 'No other device has talked to this server yet.';
        wrap.appendChild(none);
        return;
      }
      var table = document.createElement('table');
      table.className = 'table';
      var head = document.createElement('thead');
      head.innerHTML = '<tr><th>Device</th><th>Address</th><th>Last seen</th><th>Files sent</th></tr>';
      table.appendChild(head);
      var body = document.createElement('tbody');
      peersList.forEach(function (p) {
        var tr = document.createElement('tr');
        var me = p.ip === myIp;
        [
          { c: 'who', t: (me ? p.name + ' (you)' : p.name) },
          { c: 'ip', t: p.ip },
          { c: '', t: agoText(p.lastSeen) },
          { c: '', t: (p.files || 0) + (p.bytes ? ' · ' + formatBytes(p.bytes) : '') }
        ].forEach(function (cell) {
          var td = document.createElement('td');
          td.className = cell.c;
          td.textContent = cell.t;
          tr.appendChild(td);
        });
        body.appendChild(tr);
      });
      table.appendChild(body);
      wrap.appendChild(table);
    }

    function renderSession() {
      var dl = el('sessionKv');
      if (!dl) return;
      dl.textContent = '';
      var bytes = allFiles.reduce(function (s, f) { return s + (f.size || 0); }, 0);
      [
        ['Device', el('topbarDevice').textContent],
        ['Server address', location.href],
        ['Your address', myIp || '-'],
        ['Access', REQUIRES_PIN ? 'PIN protected' : 'Open to this network'],
        ['Files hosted', String(allFiles.length)],
        ['Hosted size', formatBytes(bytes)],
        ['Messages this session', String(lastMsgs.length)],
        ['Devices seen', String(peersList.length)],
        ['Session uptime', uptimeText()]
      ].forEach(function (row) {
        var dt = document.createElement('dt'); dt.textContent = row[0];
        var dd = document.createElement('dd'); dd.textContent = row[1];
        dl.appendChild(dt); dl.appendChild(dd);
      });

      var tw = el('transferWrap');
      if (tw) {
        tw.textContent = '';
        var recent = allFiles.filter(function (f) { return f.received; }).slice(-8).reverse();
        if (!recent.length) {
          var e2 = document.createElement('div');
          e2.className = 'rail-empty';
          e2.textContent = 'No files received by the phone from a browser yet.';
          tw.appendChild(e2);
        } else {
          recent.forEach(function (f) {
            var row = document.createElement('div');
            row.className = 'f-row';
            var label = document.createElement('span');
            label.textContent = f.name;
            var a = document.createElement('a');
            a.href = downloadHref(f.id);
            a.textContent = 'download';
            row.appendChild(label);
            row.appendChild(a);
            tw.appendChild(row);
          });
        }
      }
    }

    /* ===== Call overlay ===== */
    var call = { cid: '', video: false, stream: null, mic: true, cam: true, timer: null, sub: '' };

    async function startCall(video) {
      call.video = !!video;
      call.cid = 'w' + Date.now().toString(36);
      call.sub = '';
      call.mic = true; call.cam = true;
      el('callOverlay').style.display = 'flex';
      el('callAvatar').style.background = gradOf(MY_NAME);
      el('callAvatar').textContent = initialsOf(MY_NAME);
      el('callTitle').textContent = video ? 'Video call' : 'Voice call';
      el('callSub').textContent = 'Calling ' + el('topbarDevice').textContent + '…';
      el('callNote').textContent = 'Ringing the phone now. Answer it there to talk - voice and video stream between two PulseDrop apps.';
      // This call carries no browser media, so never switch the visitor's camera or mic on
      // for it: the hardware indicator would light up while nothing is transmitted.
      el('callMic').style.display = 'none';
      el('callCam').style.display = 'none';
      el('callPreview').style.display = 'none';
      fetch('/api/call/web', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
        body: JSON.stringify({ action: 'ring', cid: call.cid, name: MY_NAME, video: call.video })
      }).catch(function () {});
      if (call.timer) clearInterval(call.timer);
      call.timer = setInterval(pollCall, 1500);
    }

    async function pollCall() {
      try {
        var res = await fetch('/api/call/web', { headers: authHeaders() });
        if (!res.ok) return;
        var j = await res.json();
        var st = j.state || 'ringing';
        if (st === 'accepted') {
          if (call.sub === st) return;
          call.sub = st;
          el('callSub').textContent = 'Connected with ' + el('topbarDevice').textContent;
          el('callNote').textContent = 'The phone picked up. Talk from there - hang up here when you are done.';
        } else if (st === 'declined') {
          el('callSub').textContent = 'Call declined';
          stopCall(true);
        } else if (st === 'ended') {
          el('callSub').textContent = 'Call ended by the other side';
          stopCall(true);
        }
      } catch (e) {}
    }

    function endCall() {
      fetch('/api/call/web', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
        body: JSON.stringify({ action: 'end', cid: call.cid })
      }).catch(function () {});
      stopCall(false);
    }

    function stopCall(keepMessage) {
      if (call.timer) { clearInterval(call.timer); call.timer = null; }
      if (call.stream) { call.stream.getTracks().forEach(function (t) { t.stop(); }); call.stream = null; }
      el('callPreview').srcObject = null;
      el('callOverlay').style.display = 'none';
      if (keepMessage) toast('Call ' + el('callSub').textContent.toLowerCase());
    }

    function toggleMic() {
      call.mic = !call.mic;
      if (call.stream) call.stream.getAudioTracks().forEach(function (t) { t.enabled = call.mic; });
      el('callMic').classList.toggle('off', !call.mic);
    }

    function toggleCam() {
      call.cam = !call.cam;
      if (call.stream) call.stream.getVideoTracks().forEach(function (t) { t.enabled = call.cam; });
      el('callCam').classList.toggle('off', !call.cam);
      el('callPreview').style.opacity = call.cam ? '1' : '0.25';
    }


    /* ===== Global keys ===== */
    window.addEventListener('keydown', function (e) {
      var mod = e.metaKey || e.ctrlKey;
      if (!mod) return;
      var key = e.key.toLowerCase();
      if (key === 'k') {
        e.preventDefault();
        setTab('files');
        el('searchInput').focus();
      } else if (key === 'u' && !e.shiftKey) {
        e.preventDefault();
        if (authToken || !REQUIRES_PIN) pickFiles();
      } else if (key === 'u' && e.shiftKey) {
        e.preventDefault();
        if (authToken || !REQUIRES_PIN) pickFolder();
      } else if (key === 's') {
        e.preventDefault();
        toast('Shortcuts: Ctrl+K search - Ctrl+U upload - Ctrl+Shift+U folder - Ctrl+Enter send - Ctrl+1/2 tabs');
      } else if (key === '1') {
        e.preventDefault();
        setTab('files');
      } else if (key === '2') {
        e.preventDefault();
        setTab('chat');
      }
    });

    /* ===== Single shared poller (pauses entirely while the tab is hidden) =====
       One 1000 ms interval; gated counters give messages+typing every 2 s
       and info+files every 5 s. No per-feature intervals. */
    var tickCount = 0;
    setInterval(function () {
      if (document.hidden) return;
      if (REQUIRES_PIN && !authToken) return;
      tickCount++;
      if (tickCount % 2 === 0) fetchMessages();
      if (tickCount % 5 === 0) { fetchFiles(); fetchInfo(); }
      if (tickCount % 20 === 0) fetchThreads();
    }, 1000);

    /* ===== Boot ===== */
    el('serverUrl').textContent = location.host;
    // Some browsers have no colour-emoji font, so the tray would post empty boxes.
    if (!browserHasColorEmoji()) el('emojiBtn').style.display = 'none';
    setView('files');
    setLayout('grid');
    setHistoryLayout('grid');
    renderEmpty(el('filesGrid'), 'Loading files…');
    renderThreadRail();
    fetchThreads();
    fetchPeers();
    if (REQUIRES_PIN && !authToken) {
      showAuth();
    } else {
      el('authOverlay').style.display = 'none';
      revealApp();
      fetchFiles();
      fetchInfo();
    }
    fetchMessages();
  </script>
</body>
</html>
        """.trimIndent()
    }
}
