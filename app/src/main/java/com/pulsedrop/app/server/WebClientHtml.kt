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
  <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover, interactive-widget=resizes-content">
  <meta name="theme-color" id="themeColor" content="#FFFFFF">
  <title>PulseDrop - Private Local File Share</title>
  <script>
    /*
      One theme, and it is named here rather than left to the stylesheet: a tab that paints a dark
      frame for one frame while it works out what it should be is a flicker the user sees, and there
      is no longer any preference to read. The browser's own bar carries the same colour as --bg.
    */
    document.documentElement.setAttribute("data-theme", "light");
  </script>
  <style>
    /* ===== PulseDrop design tokens. One vocabulary, no CDN, no gradients-as-decoration. ===== */
    /* Google Sans is served by this device (same subset the app bundles), so the browser
       client types identically to the phone with no upstream available. */
    @font-face { font-family: "Google Sans"; font-weight: 400; font-display: swap; src: url("/fonts/google-sans-regular.ttf") format("truetype"); }
    @font-face { font-family: "Google Sans"; font-weight: 500; font-display: swap; src: url("/fonts/google-sans-medium.ttf") format("truetype"); }
    @font-face { font-family: "Google Sans"; font-weight: 600; font-display: swap; src: url("/fonts/google-sans-semibold.ttf") format("truetype"); }
    @font-face { font-family: "Google Sans"; font-weight: 700; font-display: swap; src: url("/fonts/google-sans-bold.ttf") format("truetype"); }

    /*
      The table. Everything below this point is written against these names, so a rule that hardcodes
      a hex is a rule nothing will ever re-theme - which is why the test that reads this file fails on
      any colour written outside it.
    */
    :root, [data-theme="light"] {
      --bg: #FFFFFF;              /* app background */
      --surface: #F8F8F8;         /* primary surface: cards, rails, panels */
      --surface-2: #FFFFFF;       /* elevated: dialogs, menus, toasts, anything floating above */
      --sunken: #F2F2F4;          /* wells: inputs, tracks, code */
      --highlight: #EEEEF1;       /* selected or hovered row wash */

      --text: #171717;
      --muted: #71717A;
      --faint: #9A9AA3;
      --text-inverse: #FFFFFF;    /* ink for a surface painted with --text */

      --hairline: #E7E7EA;
      --hairline-strong: #D3D3D8;

      --accent: #4F46E5;
      --accent-hover: #4338CA;
      --accent-tint: #E5E4FC;
      --accent-tint-fg: #312C8C;
      --on-accent: #FFFFFF;

      /* Indicator and text are separate colours on purpose: the vivid one is for a dot, an icon or
         a border, and only the darker -text variant clears AA as words on the tint beside it. */
      --success: #16A34A;
      --success-text: #166534;
      --success-tint: #DCFCE8;
      --warn: #B45309;
      --warn-text: #854D0E;
      --warn-tint: #FDEBD3;
      --error: #DC2626;
      --error-text: #991B1B;
      --error-tint: #FEE2E2;

      --t-blue-bg: #E5E4FC;   --t-blue-fg: #312C8C;
      --t-green-bg: #DCFCE8;  --t-green-fg: #065F32;
      --t-violet-bg: #EDE9FE; --t-violet-fg: #5B21B6;
      --t-amber-bg: #FDEBD3;  --t-amber-fg: #92400E;
      --t-slate-bg: #EEEEF1;  --t-slate-fg: #3F3F46;

      --scrim: rgba(9,9,11,0.5);
      --shadow-1: 0 1px 2px rgba(23,23,23,0.05), 0 1px 3px rgba(23,23,23,0.06);
      --shadow-2: 0 6px 20px rgba(23,23,23,0.10);
      --focus-ring: rgba(79,70,229,0.45);

      /*
        The media stage - the call surface and the full-screen attachment viewer. These are the
        phone's PulseStageColors, role for role, and the parity test holds them there: a scrim is
        --bg with alpha, a line or chip is --muted with alpha, a panel is the elevated surface. The
        feed itself stays black (--stage-black) whatever is painted around it.
      */
      --stage-bg: #FFFFFF;
      --stage-surface: #F8F8F8;
      --stage-panel: rgba(255, 255, 255, .86);
      --stage-line: rgba(113, 113, 122, .3);
      --stage-line-soft: rgba(113, 113, 122, .2);
      --stage-line-faint: rgba(113, 113, 122, .14);
      --stage-wash: rgba(113, 113, 122, .06);
      --stage-wash-hover: rgba(113, 113, 122, .16);
      --stage-chip: rgba(113, 113, 122, .18);
      --stage-scrim: rgba(255, 255, 255, .74);
      --stage-scrim-soft: rgba(255, 255, 255, .62);
      --stage-scrim-strong: rgba(255, 255, 255, .92);
      --stage-scrim-mid: rgba(255, 255, 255, .4);
      --stage-clear: rgba(255, 255, 255, 0);
      --stage-text: #171717;
      --stage-muted: #71717A;
      --stage-accent: #312C8C;
      --stage-good: #16A34A;
      --stage-on-good: #FFFFFF;
      --stage-good-tint: #DCFCE8;
      --stage-on-good-tint: #166534;
      --stage-bad: #DC2626;
      --stage-on-bad: #FFFFFF;
      --stage-black: #000000;
      /* Initials sit on a per-contact identity colour, not a theme colour - see PulseAvatarPalette. */
      --avatar-ink: #FFFFFF;

      --radius: 16px;
      --topbar-h: 56px;
      --tabbar-h: 60px;
    }

    * { box-sizing: border-box; margin: 0; padding: 0; }
    html, body { height: 100%; }
    body {
      font-family: "Google Sans", system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
      background: var(--bg);
      color: var(--text);
      -webkit-font-smoothing: antialiased;
      overflow: hidden;
    }
    svg { display: block; flex-shrink: 0; }
    button, input, textarea, select { font-family: inherit; }
    /* A ring that survives both tables: a hard accent line plus a soft halo, so focus reads on
       near-black and on white without either theme needing its own colour here. */
    :focus-visible {
      outline: 2px solid var(--accent);
      outline-offset: 2px;
      box-shadow: 0 0 0 4px var(--focus-ring);
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
    .topbar-act {
      display: inline-flex; align-items: center; justify-content: center; gap: 6px;
      min-width: 36px; height: 34px; padding: 0 9px;
      background: transparent; border: 1px solid var(--hairline); border-radius: 10px;
      color: var(--muted); font-size: 12px; font-weight: 600; cursor: pointer; white-space: nowrap;
    }
    .topbar-act:hover { background: var(--accent-tint); color: var(--accent); border-color: var(--accent); }
    .topbar-act.secure { color: var(--warn); border-color: var(--warn-tint); background: var(--warn-tint); }
    .topbar-act.secure:hover { filter: brightness(0.96); }
    .topbar-act.spin svg { animation: pdspin 0.8s linear infinite; }
    @keyframes pdspin { to { transform: rotate(360deg); } }
    .status-pill.me { background: var(--surface-2); color: var(--muted); border: 1px solid var(--hairline); cursor: pointer; }
    .status-pill.me b { font-weight: 700; color: var(--text); }

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
      background: var(--accent); color: var(--on-accent);
      display: flex; align-items: center; justify-content: center;
    }
    .brand-name { font-size: 17px; font-weight: 700; letter-spacing: -0.3px; }
    .brand-name em { font-style: normal; color: var(--accent); }
    .brand-sub { font-size: 11px; color: var(--muted); margin-top: 1px; }

    /* ===== Section heading ===== */
    .sec-title {
      font-size: 11px; font-weight: 700; letter-spacing: 0.7px; text-transform: uppercase;
      color: var(--muted); padding: 0 4px;
    }
    .ic-blue { background: var(--t-blue-bg); color: var(--t-blue-fg); }
    .ic-green { background: var(--t-green-bg); color: var(--t-green-fg); }
    .ic-violet { background: var(--t-violet-bg); color: var(--t-violet-fg); }
    .ic-amber { background: var(--t-amber-bg); color: var(--t-amber-fg); }
    .ic-slate { background: var(--t-slate-bg); color: var(--t-slate-fg); }

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

    /* ===== Upload page ===== */
    .upload-pane {
      width: auto; max-width: 860px; margin: 0 auto; padding: 26px 22px 40px; gap: 18px;
      border-right: none;
    }
    .up-head { padding: 0 4px; }
    .up-title { font-size: 21px; font-weight: 800; letter-spacing: -0.5px; }
    .up-sub { font-size: 12.5px; color: var(--muted); margin-top: 5px; line-height: 1.55; }
    .dropzone-lg { padding: 44px 20px; gap: 8px; border-radius: 22px; }
    .dropzone-lg .dropzone-icon { width: 52px; height: 52px; border-radius: 16px; }
    .dropzone-lg h3 { font-size: 15px; }
    .dropzone-lg p { font-size: 12.5px; max-width: 420px; }
    .up-field {
      background: var(--surface); border: 1px solid var(--hairline);
      border-radius: var(--radius); padding: 14px 16px; box-shadow: var(--shadow-1);
    }
    .up-field .select { width: 100%; margin-top: 8px; }
    .dev-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 9px; font-size: 12px; color: var(--muted); }
    .dev-chip {
      display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600;
      border: 1px solid var(--hairline); border-radius: 999px; padding: 4px 10px; color: var(--text);
    }
    .dev-chip::before {
      content: ""; width: 7px; height: 7px; border-radius: 50%; background: var(--muted); flex-shrink: 0;
    }
    .dev-chip.live::before { background: var(--success); }
    .link-btn {
      appearance: none; border: none; background: none; padding: 0; cursor: pointer;
      font: inherit; font-weight: 700; color: var(--accent);
    }
    .link-btn:hover { text-decoration: underline; }
    .up-hint { font-size: 11.5px; color: var(--muted); margin-top: 9px; line-height: 1.55; }

    /* ===== Files filter bar ===== */
    .filter-bar {
      flex-shrink: 0; display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
      padding: 10px 20px; background: var(--surface); border-bottom: 1px solid var(--hairline);
    }
    .filter-bar .search { flex: 1 1 240px; max-width: 360px; }
    .filter-bar .chips { flex: 1 1 auto; }

    .btn {
      border: none; border-radius: 12px; cursor: pointer;
      font-size: 12.5px; font-weight: 600; padding: 9px 12px;
      display: inline-flex; align-items: center; justify-content: center; gap: 6px; white-space: nowrap;
    }
    .btn-filled { background: var(--accent); color: var(--on-accent); }
    .btn-filled:hover { background: var(--accent-hover); }
    .btn-quiet { background: var(--surface); color: var(--muted); border: 1px solid var(--hairline); }
    .btn-quiet:hover { background: var(--highlight); color: var(--text); }

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
      background: var(--surface-2); border: 1px solid var(--hairline); border-radius: 12px;
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
    .chip:hover { background: var(--highlight); }
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
      background: var(--surface-2); color: var(--text);
      border: 1px solid var(--hairline); border-radius: 10px;
      padding: 8px 10px; font-size: 12px; font-weight: 600; text-decoration: none;
      transition: background 0.15s, color 0.15s, border-color 0.15s;
    }
    .dl-btn:hover { background: var(--accent); color: var(--on-accent); border-color: var(--accent); }
    .icon-btn {
      appearance: none; border: 1px solid var(--hairline);
      background: var(--surface-2); color: var(--muted);
      width: 34px; height: 34px; border-radius: 10px; cursor: pointer; flex-shrink: 0;
      display: flex; align-items: center; justify-content: center;
      transition: background 0.15s, color 0.15s, border-color 0.15s;
    }
    .icon-btn:hover { background: var(--error-tint); color: var(--error); border-color: var(--error); }
    .icon-btn.soft:hover { background: var(--accent-tint); color: var(--accent); border-color: var(--accent); }
    .folder-toggle {
      appearance: none; border: none; background: transparent; cursor: pointer;
      color: var(--accent); font-size: 12px; font-weight: 700;
      padding: 7px 10px; margin: -7px 0; text-align: left; flex-shrink: 0;
      border-radius: 8px;
    }
    .folder-toggle:hover { text-decoration: underline; }
    .folder-toggle:active { background: var(--accent-tint); }
    .folder-contents { display: none; flex-direction: column; gap: 5px; border-top: 1px dashed var(--hairline); padding-top: 8px; }
    .folder-contents.open { display: flex; }
    .f-row {
      display: flex; align-items: center; justify-content: space-between; gap: 8px;
      font-size: 12px; color: var(--text); background: var(--surface-2); border-radius: 8px; padding: 6px 9px;
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
    /* A grid would otherwise shrink-wrap the empty state into one cell. */
    .file-list > .empty { grid-column: 1 / -1; }
    .empty-cta {
      appearance: none; cursor: pointer; font: inherit; font-weight: 700; font-size: 12.5px;
      color: var(--on-accent); background: var(--accent); border: none; border-radius: 999px; padding: 9px 18px;
    }
    .empty-cta:hover { filter: brightness(1.07); }

    /* ===== Files: the empty shelf ===== */
    .files-empty {
      grid-column: 1 / -1;
      display: flex; flex-direction: column; align-items: center; text-align: center;
      padding: clamp(30px, 7vh, 72px) 20px 44px;
    }
    .fe-art { position: relative; width: 140px; height: 96px; margin-bottom: 26px; }
    .fe-card {
      position: absolute; top: 8px; left: 50%; width: 74px; height: 58px; margin-left: -37px;
      border-radius: 12px; background: var(--surface);
      border: 1px solid var(--hairline); box-shadow: var(--shadow-1);
    }
    .fe-card::after {
      content: ""; position: absolute; top: 13px; left: 12px; right: 12px; height: 6px;
      border-radius: 999px; background: var(--t-slate-bg);
    }
    .fe-card.c1 { transform: rotate(-15deg) translateX(-28px) translateY(9px); opacity: .5; }
    .fe-card.c2 { transform: rotate(10deg) translateX(26px) translateY(5px); opacity: .72; }
    .fe-card.c3 {
      top: 14px; width: 86px; height: 68px; margin-left: -43px;
      border-color: var(--hairline-strong); box-shadow: var(--shadow-2);
    }
    .fe-card.c3::after {
      left: 14px; right: 30px;
      box-shadow: 0 14px 0 var(--t-slate-bg), 0 28px 0 var(--t-blue-bg);
    }
    .fe-plus {
      position: absolute; right: 2px; bottom: 0; width: 34px; height: 34px; border-radius: 50%;
      background: var(--accent); color: var(--on-accent); display: flex; align-items: center; justify-content: center;
      box-shadow: var(--shadow-2);
    }
    .fe-title { font-size: 19px; font-weight: 800; letter-spacing: -.3px; color: var(--text); }
    .fe-sub { margin-top: 9px; max-width: 46ch; font-size: 13.5px; line-height: 1.62; color: var(--muted); }
    .fe-ctas { margin-top: 22px; display: flex; flex-wrap: wrap; gap: 10px; justify-content: center; }
    .fe-btn {
      appearance: none; cursor: pointer; font: inherit; font-weight: 700; font-size: 13px;
      display: inline-flex; align-items: center; gap: 7px; border-radius: 999px; padding: 11px 20px;
      border: 1px solid transparent; transition: transform .12s ease, border-color .12s ease, color .12s ease;
    }
    .fe-btn:hover { transform: translateY(-1px); }
    .fe-btn.primary { background: var(--accent); color: var(--on-accent); box-shadow: var(--shadow-2); }
    .fe-btn.primary:hover { background: var(--accent-hover); }
    .fe-btn.ghost { background: var(--surface); color: var(--text); border-color: var(--hairline-strong); }
    .fe-btn.ghost:hover { border-color: var(--accent); color: var(--accent); }
    .fe-hints { margin-top: 26px; display: flex; flex-wrap: wrap; gap: 8px 16px; justify-content: center; }
    .fe-hint { font-size: 11.5px; font-weight: 650; color: var(--muted); display: inline-flex; align-items: center; gap: 6px; }
    .fe-hint i { width: 5px; height: 5px; border-radius: 50%; background: var(--success); }
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
      appearance: none; width: 38px; height: 38px; border-radius: 50%;
      border: 1px solid var(--hairline); background: var(--surface); color: var(--muted);
      display: inline-flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0;
      transition: background 0.15s, color 0.15s, border-color 0.15s;
    }
    .icon-round:hover { background: var(--accent-tint); color: var(--accent); border-color: var(--accent); }
    .rail-search { padding: 0 14px 10px; }
    .rail-search input {
      width: 100%; box-sizing: border-box; font-size: 13px; color: var(--text); outline: none;
      border: 1px solid var(--hairline); background: var(--surface-2); border-radius: 12px; padding: 9px 12px;
    }
    .rail-search input:focus { border-color: var(--accent); background: var(--surface); }
    .rail-new { display: none; flex-wrap: wrap; gap: 6px; padding: 0 14px 10px; }
    .rail-new.open { display: flex; }
    .rail-new input {
      flex: 1; min-width: 0; font-size: 12.5px; color: var(--text); outline: none;
      border: 1px solid var(--accent); background: var(--surface); border-radius: 12px; padding: 8px 12px;
    }
    .new-peers { display: flex; flex-wrap: wrap; gap: 6px; width: 100%; margin-top: 2px; }
    .new-peer {
      appearance: none; cursor: pointer; font-size: 11.5px; font-weight: 600;
      border: 1px solid var(--hairline); background: var(--surface-2); color: var(--text);
      border-radius: 999px; padding: 5px 10px;
    }
    .new-peer:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-tint); }
    .new-peer.on { border-color: var(--accent); color: var(--accent); background: var(--accent-tint); }
    .new-peer.idle { color: var(--muted); }
    .new-peers-empty { font-size: 11.5px; color: var(--muted); width: 100%; }
    .rail-list { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 0 8px 10px; }
    .rail-empty { padding: 26px 16px; font-size: 12.5px; color: var(--muted); text-align: center; line-height: 1.55; }
    .rail-item {
      width: 100%; box-sizing: border-box; appearance: none; text-align: left; cursor: pointer;
      display: flex; align-items: center; gap: 10px; padding: 10px; border-radius: 14px;
      border: 1px solid transparent; background: none; color: var(--text); transition: background 0.12s;
    }
    .rail-item:hover { background: var(--highlight); }
    .rail-item.active { background: var(--accent-tint); border-color: var(--accent); }
    .ravatar {
      width: 42px; height: 42px; border-radius: 50%; flex-shrink: 0;
      display: flex; align-items: center; justify-content: center;
      font-size: 15px; font-weight: 800; color: var(--avatar-ink); letter-spacing: -0.5px;
    }
    .rbody { flex: 1; min-width: 0; }
    .rtop { display: flex; align-items: baseline; gap: 6px; }
    .rname { font-size: 13.5px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .rtime { margin-left: auto; font-size: 10.5px; color: var(--muted); flex-shrink: 0; }
    .rprev { font-size: 12px; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 2px; }
    .rprev b { color: var(--text); font-weight: 600; }
    .rbadge {
      min-width: 19px; height: 19px; padding: 0 5px; border-radius: 999px; flex-shrink: 0;
      background: var(--accent); color: var(--on-accent); font-size: 10.5px; font-weight: 800;
      display: inline-flex; align-items: center; justify-content: center;
    }
    .tbadge {
      min-width: 18px; height: 18px; padding: 0 5px; border-radius: 999px;
      background: var(--accent); color: var(--on-accent); font-size: 10.5px; font-weight: 700;
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
      font-family: "Google Sans", system-ui, "Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji", "Twemoji Mozilla", sans-serif;
      font-size: 14px; line-height: 1.45; word-break: break-word; overflow-wrap: anywhere; white-space: pre-wrap;
      background: var(--hairline); color: var(--text);
      border-bottom-left-radius: 18px;
    }
    .bubble-row.mine .bubble { background: var(--accent); color: var(--on-accent); }
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
    /* Reply, retraction and the composer's reply context. All token-driven, nothing hardcoded. */
    .bubble.deleted {
      background: transparent; border: 1px dashed var(--hairline-strong);
      color: var(--muted); font-style: italic;
    }
    .reply-quote {
      display: flex; gap: 8px; margin: 0 0 6px; padding: 5px 9px 5px 7px;
      border-radius: 10px; background: var(--hairline); max-width: 100%;
    }
    .bubble-row.mine .reply-quote { background: var(--accent-tint); }
    .reply-quote .rq-bar { width: 2px; border-radius: 2px; background: var(--accent); flex: none; }
    .reply-quote .rq-body { min-width: 0; }
    .reply-quote .rq-who { font-size: 11px; font-weight: 700; color: var(--accent); }
    .reply-quote .rq-text {
      font-size: 11px; color: var(--muted); overflow: hidden;
      display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
    }
    .bubble-row.mine .reply-quote .rq-text { color: var(--text); }
    .bubble-actions {
      display: inline-flex; gap: 2px; margin-left: auto;
      opacity: 0; transition: opacity 120ms ease;
    }
    .bubble-row:hover .bubble-actions, .bubble-actions:focus-within { opacity: 1; }
    .bubble-actions button {
      appearance: none; border: none; background: transparent; color: var(--muted);
      cursor: pointer; font: inherit; font-size: 11px; font-weight: 700;
      min-width: 34px; min-height: 34px; padding: 6px 8px; border-radius: 9px;
    }
    .bubble-actions button:hover { background: var(--hairline); color: var(--text); }
    .bubble-actions button:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
    .bubble-actions button.danger:hover { color: var(--error); }
    /* A finger has no hover, so the actions must not depend on it. */
    @media (hover: none) { .bubble-actions { opacity: .75; } }
    .reply-bar {
      display: none; align-items: center; gap: 8px; margin: 0 0 7px; padding: 6px 8px 6px 11px;
      border-radius: 12px; background: var(--hairline); font-size: 12px; color: var(--text);
    }
    .reply-bar.show { display: flex; }
    .reply-bar .rb-body { min-width: 0; flex: 1; }
    .reply-bar .rb-who { font-size: 11px; font-weight: 700; color: var(--accent); }
    .reply-bar .rb-text { color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .chat-empty {
      margin: auto; display: flex; flex-direction: column; align-items: center; gap: 10px;
      color: var(--muted); font-size: 13px; text-align: center; padding: 24px;
    }
    .new-pill {
      position: absolute; left: 50%; bottom: 14px; transform: translateX(-50%);
      display: none; align-items: center; gap: 6px;
      background: var(--accent); color: var(--on-accent); border: none; border-radius: 999px;
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
      background: var(--surface-2); border: 1px solid var(--hairline); border-radius: 14px;
      padding: 11px 14px; font-size: 14px; color: var(--text); outline: none;
      min-height: 44px; max-height: 120px; line-height: 1.4;
    }
    .composer textarea:focus { border-color: var(--accent); background: var(--surface); }
    .composer-wrap { position: relative; flex-shrink: 0; }
    .emoji-pop {
      display: none; position: absolute; left: 12px; right: 12px; bottom: calc(100% + 8px);
      background: var(--surface); border: 1px solid var(--hairline); border-radius: 16px;
      box-shadow: var(--shadow-2);
      padding: 10px; max-height: 190px; overflow-y: auto;
      grid-template-columns: repeat(8, 1fr); gap: 2px; z-index: 6;
    }
    .emoji-pop.open { display: grid; }
    .emoji-pop button {
      appearance: none; border: none; background: none; cursor: pointer;
      font-size: 20px; line-height: 1; padding: 6px 2px; border-radius: 9px;
    }
    .emoji-pop button:hover { background: var(--highlight); }
    .attach-btn.is-busy { opacity: 0.55; pointer-events: none; }
    .send-btn {
      width: 44px; height: 44px; border-radius: 14px; border: none; cursor: pointer; flex-shrink: 0;
      background: var(--accent); color: var(--on-accent);
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
      background: var(--accent); color: var(--on-accent); font-size: 10px; font-weight: 700;
      display: none; align-items: center; justify-content: center;
    }

    /* ===== Auth modal ===== */
    .overlay {
      position: fixed; inset: 0; background: var(--scrim);
      display: flex; align-items: flex-start; justify-content: center; z-index: 1000; padding: 20px;
      /* A centred flex child that does not fit is clipped on both ends; auto margins plus
         scrolling keep the PIN field reachable when the keyboard takes half the screen. */
      overflow-y: auto;
    }
    .modal {
      /* Elevated, not merely a surface: a dialog has to separate from the grey panel behind it,
         and in the dark theme that separation is one step up the ramp rather than a white card. */
      background: var(--surface-2); border: 1px solid var(--hairline); border-radius: 22px; padding: 28px;
      width: 100%; max-width: 380px; text-align: center; margin: auto;
      display: flex; flex-direction: column; gap: 14px; box-shadow: var(--shadow-2);
    }
    .modal-icon {
      width: 56px; height: 56px; border-radius: 17px; margin: 0 auto;
      background: var(--accent-tint); color: var(--accent);
      display: flex; align-items: center; justify-content: center;
    }
    .pin-input {
      background: var(--sunken); border: 2px solid var(--hairline-strong); color: var(--text);
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
      background: var(--text); color: var(--text-inverse);
      font-size: 13px; font-weight: 600; padding: 11px 18px; border-radius: 12px;
      box-shadow: var(--shadow-2); opacity: 0; pointer-events: none;
      transition: opacity 0.2s, transform 0.2s; z-index: 1100; max-width: 90vw; text-align: center;
    }
    .toast.show { opacity: 1; transform: translateX(-50%) translateY(0); }
    /* On a phone the bottom edge is spoken for by the tab bar (and, in a call, the dock),
       so a toast parked there reads as part of them and hides the controls behind it. */
    @media (max-width: 959px) {
      .toast { bottom: calc(env(safe-area-inset-bottom, 0px) + 78px); }
      .toast.in-call { bottom: calc(env(safe-area-inset-bottom, 0px) + 142px); }
    }

    /* ===== Left navigation rail ===== */
    .nav-rail {
      width: 208px; flex-shrink: 0; display: flex; flex-direction: column; gap: 4px;
      padding: 14px 12px; background: var(--surface); border-right: 1px solid var(--hairline);
    }
    /* Chats needs the width for the thread list, so the sidebar folds to icons there. */
    .nav-rail.mini { width: 74px; align-items: center; padding: 14px 0; gap: 6px; }
    .nav-rail.mini .nav-btn { flex-direction: column; width: 58px; gap: 4px; padding: 8px 0 6px; font-size: 10px; }
    .nav-rail.mini .nav-lab { display: none; }
    .nav-rail.mini .nav-mark { margin-bottom: 8px; }
    .nav-rail.mini #railToggle svg { transform: rotate(180deg); }
    .nav-rail.mini #railToggle { margin-top: auto; }
    .nav-rail.mini #railToggle svg { transform: rotate(180deg); }
    .nav-mark {
      width: 38px; height: 38px; border-radius: 13px; margin-bottom: 8px;
      background: var(--accent); color: var(--on-accent); display: flex; align-items: center; justify-content: center;
    }
    .nav-btn {
      appearance: none; border: none; background: none; cursor: pointer;
      border-radius: 13px; display: flex; align-items: center; gap: 12px; padding: 9px 12px;
      color: var(--muted); font-size: 13px; font-weight: 700; letter-spacing: -0.1px;
      transition: background 0.15s, color 0.15s;
    }
    .nav-btn:hover { background: var(--highlight); color: var(--text); }
    .nav-btn.active { background: var(--accent-tint); color: var(--accent); }
    .nav-badge {
      position: absolute; top: -4px; right: -8px; min-width: 17px; height: 17px; padding: 0 4px;
      border-radius: 999px; background: var(--accent); color: var(--on-accent); font-size: 9.5px; font-weight: 800;
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
    .seg button.active { background: var(--accent); color: var(--on-accent); }
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
      color: var(--muted); padding: 11px 14px; border-bottom: 1px solid var(--hairline); background: var(--sunken);
    }
    .table td { padding: 11px 14px; font-size: 13px; border-bottom: 1px solid var(--hairline); }
    .table tr:last-child td { border-bottom: none; }
    .table .who { font-weight: 700; }
    .table .ip { color: var(--muted); font-variant-numeric: tabular-nums; }
    /* Five columns will not squeeze into a phone. Let the table slide sideways rather than
       crushing an address into three wrapped lines. */
    #peersWrap { overflow-x: auto; -webkit-overflow-scrolling: touch; }
    #peersWrap .table { min-width: 540px; }
    .kv { display: grid; grid-template-columns: 150px 1fr; gap: 8px 14px; font-size: 13px; }
    .kv dt { color: var(--muted); font-weight: 600; }
    .kv dd { margin: 0; font-weight: 700; word-break: break-word; }
    .panel { background: var(--surface); border: 1px solid var(--hairline); border-radius: var(--radius); padding: 16px 18px; }
    .panel + .panel { margin-top: 14px; }
    .panel h3 { font-size: 13.5px; font-weight: 800; letter-spacing: -0.2px; margin-bottom: 12px; }
    .self-name-row { display: flex; gap: 8px; }
    .self-name-row input {
      flex: 1; min-width: 0; border: 1px solid var(--hairline); border-radius: 12px;
      background: var(--surface-2); color: var(--text); font-size: 13px; padding: 9px 12px;
    }
    .self-name-row input:focus { outline: none; border-color: var(--accent); }
    .self-name-hint { font-size: 11.5px; color: var(--muted); margin-top: 8px; line-height: 1.5; }

    /* ===== Call: a full-page stage, not a popup ===== */
    .call-screen {
      position: fixed; inset: 0; z-index: 130; background: var(--stage-bg); color: var(--stage-text);
      display: none; flex-direction: column;
    }
    .call-screen.show { display: flex; }
    .call-stage {
      position: relative; flex: 1; min-height: 0; overflow: hidden;
      display: flex; align-items: center; justify-content: center;
    }
    /* Two slots: one fills the stage, one rides the corner. Tapping the corner tile swaps them,
       dragging it parks the tile wherever you like - the Messenger interaction people expect. */
    .feed-slot { position: absolute; overflow: hidden; }
    .feed-slot.big { inset: 0; z-index: 0; background: var(--stage-bg); }
    .feed-slot.small {
      inset: auto 22px 22px auto; width: 172px; aspect-ratio: 4 / 3; height: auto; z-index: 4;
      border-radius: 16px; overflow: hidden; background: var(--stage-surface); cursor: grab; touch-action: none;
      border: 1px solid var(--stage-line); box-shadow: 0 16px 34px rgba(0, 0, 0, .5);
    }
    .feed-slot.small.dragging { cursor: grabbing; }
    .feed-slot img, .feed-slot video {
      position: absolute; inset: 0; width: 100%; height: 100%; display: block; object-fit: contain;
    }
    .feed-slot.small img, .feed-slot.small video { object-fit: cover; }
    .feed-slot video.self-cam { transform: scaleX(-1); }
    .call-remote { background: var(--stage-bg); }
    .call-remote.cover { object-fit: cover; }
    .call-poster { position: relative; z-index: 1; display: flex; flex-direction: column; align-items: center; padding: 24px; text-align: center; }
    .call-poster.hide { display: none; }
    .call-avatar {
      width: 116px; height: 116px; border-radius: 50%;
      display: flex; align-items: center; justify-content: center;
      font-size: 42px; font-weight: 800; color: var(--avatar-ink);
      box-shadow: 0 18px 44px rgba(0, 0, 0, .45);
    }
    .call-peer { font-size: 24px; font-weight: 800; letter-spacing: -.6px; margin-top: 16px; }
    .call-status { font-size: 13.5px; color: var(--stage-muted); margin-top: 7px; }
    .call-top {
      position: absolute; top: 0; left: 0; right: 0; z-index: 3;
      display: flex; align-items: flex-start; justify-content: space-between; gap: 14px;
      padding: 22px 26px; background: linear-gradient(180deg, var(--stage-scrim), var(--stage-clear));
      pointer-events: none;
    }
    .call-kicker { font-size: 10.5px; font-weight: 800; letter-spacing: 1px; text-transform: uppercase; color: var(--stage-accent); }
    .call-name { font-size: 17px; font-weight: 800; letter-spacing: -.3px; margin-top: 3px; }
    .call-clock {
      font-size: 13px; font-weight: 700; font-variant-numeric: tabular-nums; color: var(--stage-text);
      background: var(--stage-chip); border-radius: 999px; padding: 6px 13px;
    }
    .call-tag {
      position: absolute; z-index: 3; left: 26px; bottom: 22px; display: none;
      align-items: center; gap: 8px; font-size: 12px; font-weight: 700;
      background: var(--stage-good-tint); color: var(--stage-on-good-tint); padding: 8px 14px; border-radius: 999px;
    }
    .call-tag.show { display: flex; }
    .call-pip-off {
      position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
      font-size: 11.5px; font-weight: 700; color: var(--stage-muted); text-align: center; padding: 8px;
    }
    .call-pip.live .call-pip-off { display: none; }
    .pip-hint {
      position: absolute; z-index: 2; left: 8px; top: 8px; display: none;
      align-items: center; gap: 5px; font-size: 10px; font-weight: 800; letter-spacing: .3px;
      color: var(--stage-text); background: var(--stage-scrim-soft); padding: 4px 9px; border-radius: 999px;
      pointer-events: none;
    }
    .feed-slot.small .pip-hint { display: flex; }
    .pip-flip {
      position: absolute; z-index: 3; right: 7px; top: 7px; display: none;
      width: 34px; height: 34px; border-radius: 50%; border: none; cursor: pointer;
      align-items: center; justify-content: center;
      color: var(--stage-text); background: var(--stage-scrim-soft);
    }
    .pip-flip.show { display: flex; }
    .pip-flip svg { pointer-events: none; }
    .call-dock {
      position: relative; z-index: 5; display: flex; align-items: center; justify-content: center;
      gap: 16px; padding: 22px 18px calc(30px + env(safe-area-inset-bottom, 0px));
      background: linear-gradient(0deg, var(--stage-scrim-strong), var(--stage-scrim-mid) 65%, var(--stage-clear));
    }
    .dock-cell { display: flex; flex-direction: column; align-items: center; gap: 7px; }
    .dock-btn {
      appearance: none; border: none; cursor: pointer; width: 58px; height: 58px; border-radius: 50%;
      display: inline-flex; align-items: center; justify-content: center;
      color: var(--stage-text); background: var(--stage-chip);
      transition: transform .12s ease, background .12s ease;
    }
    .dock-btn:hover { background: var(--stage-line); }
    .dock-btn:active { transform: scale(.93); }
    .dock-btn.active { background: var(--stage-text); color: var(--stage-bg); }
    .dock-btn.danger { background: var(--stage-bad); color: var(--stage-on-bad); }
    .dock-btn.good { background: var(--stage-good); color: var(--stage-on-good); }
    .dock-btn[disabled] { opacity: .35; cursor: not-allowed; }
    /* The slash paths live in the same svg as their base icon and only surface when the
       control is in its off/engaged state - an icon that visibly changes reads at a glance. */
    .dock-btn .sl { display: none; }
    .dock-btn.active .sl, .dock-btn.slated .sl { display: inline; }
    /* Mute shows its slash when engaged; camera shows its slash when the lens is closed. */
    #callCam.active .sl { display: none; }
    .dock-lab { font-size: 10.5px; font-weight: 700; letter-spacing: .2px; color: var(--stage-muted); }
    .call-hint {
      position: absolute; z-index: 4; left: 50%; transform: translateX(-50%); bottom: 178px;
      font-size: 11.5px; color: var(--stage-text); background: var(--stage-panel);
      border: 1px solid var(--stage-line-soft); padding: 9px 15px; border-radius: 999px;
      display: none; max-width: min(520px, 90vw); text-align: center; line-height: 1.45;
    }
    .call-hint.show { display: block; }
    @media (max-width: 720px) {
      .feed-slot.small { width: 116px; inset: auto 14px 14px auto; }
      .dock-btn { width: 52px; height: 52px; }
      .call-dock { gap: 12px; }
    }

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
      border: 1px solid var(--hairline); background: var(--surface-2); color: var(--text);
    }
    .att-btn:hover { background: var(--accent-tint); border-color: var(--accent); color: var(--accent); }
    .att-btn.prim { background: var(--accent); border-color: var(--accent); color: var(--on-accent); }
    .att-btn.prim:hover { background: var(--accent-hover); color: var(--on-accent); }

    /* ===== Preview lightbox ===== */
    .lb {
      position: fixed; inset: 0; z-index: 95; display: none; flex-direction: column;
      background: var(--stage-bg);
    }
    .lb.open { display: flex; }
    .lb-head {
      display: flex; align-items: center; gap: 10px; padding: 9px 12px;
      border-bottom: 1px solid var(--stage-line-faint); color: var(--stage-text); flex: 0 0 auto;
    }
    .lb-title { flex: 1; min-width: 0; font-size: 13.5px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .lb-sub { flex: 0 0 auto; font-size: 11.5px; color: var(--stage-muted); }
    .lb-tools { display: flex; align-items: center; gap: 5px; flex: 0 0 auto; }
    .lb-btn {
      font: inherit; width: 32px; height: 32px; border-radius: 9px; cursor: pointer;
      display: inline-flex; align-items: center; justify-content: center;
      border: 1px solid var(--stage-line-soft); background: var(--stage-wash); color: var(--stage-text);
    }
    .lb-btn:hover { background: var(--stage-wash-hover); }
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
    .lb-video { width: min(900px, 94vw); background: var(--stage-black); }
    /* The white here is the paper of the document, not a surface colour - a PDF page stays white
       in either theme, exactly like a printed page. */
.lb-pdf { width: min(1000px, 94vw); height: calc(100vh - 120px); background: #fff; border: none; }
    .lb-audio-wrap { display: flex; flex-direction: column; align-items: center; gap: 16px; padding: 24px; }
    .lb-audio { width: min(520px, 86vw); }
    .lb-text { position: absolute; inset: 0; overflow: auto; padding: 16px 18px; }
    .lb-text pre {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 12.5px; line-height: 1.65; color: var(--stage-text); white-space: pre-wrap; word-break: break-word;
    }
    .lb-none { text-align: center; color: var(--stage-text); padding: 28px; max-width: 420px; }
    .lb-none .nm { font-size: 14px; font-weight: 600; margin-bottom: 4px; word-break: break-all; }
    .lb-none .mm { font-size: 12px; color: var(--stage-muted); margin-bottom: 16px; }
    .lb-hint {
      position: absolute; left: 0; right: 0; bottom: 10px; text-align: center;
      font-size: 11px; color: var(--stage-muted); pointer-events: none;
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
      .topbar-device { max-width: 30vw; }
      /* Two buttons and an address will not fit above a phone's own name - the identity chip
         lives in the Details view too, so it yields here. */
      .topbar-url { max-width: 20vw; }
      #meChip { display: none !important; }
      .topbar-act.secure span { display: none; }
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
      .bubble-col { max-width: 82%; }
      /* Under 16px, iOS Safari zooms the page to the text on focus and the visitor loses
         the view they were looking at. */
      input, textarea, select { font-size: 16px; }
      .search-box input { font-size: 16px; }
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
      <!-- Browsers only hand out mic/camera/screen on a secure origin, so an http tab needs a
           way to get there the moment the phone's https port comes up - not just a toast. -->
      <button class="topbar-act secure" id="secureBtn" style="display: none" aria-label="Switch to secure mode" onclick="goSecure()">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="4" y="10" width="16" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg>
        <span>Secure mode</span>
      </button>
      <div class="status-pill me" id="meChip" style="display: none" title="The address this phone sees you as"><span id="meChipTxt">You</span></div>
      <button class="topbar-act" id="refreshBtn" aria-label="Refresh" title="Refresh" onclick="manualRefresh()">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a9 9 0 1 1-3-6.7"/><path d="M21 4v5h-5"/></svg>
      </button>
    </header>

    <div class="shell">

      <!-- ===== Navigation rail ===== -->
      <nav class="nav-rail" id="navRail" aria-label="Sections">
        <div class="nav-mark">
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v10"/><path d="m8 9 4 4 4-4"/><path d="M5 19h14"/></svg>
        </div>
        <button class="nav-btn active" id="navFiles" aria-label="Files" data-view="files" aria-current="true" onclick="setView('files')">
          <span class="nav-ico"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.6a2 2 0 0 1-1.7-.9L9.6 4.2A2 2 0 0 0 7.9 3.3H4a2 2 0 0 0-2 2v12.7A2 2 0 0 0 4 20Z"/></svg></span>
          <span class="nav-lab">Files</span>
        </button>
        <button class="nav-btn" id="navUpload" aria-label="Upload" data-view="upload" aria-current="false" onclick="setView('upload')">
          <span class="nav-ico"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 16V4"/><path d="m7 9 5-5 5 5"/><path d="M4 20h16"/></svg></span>
          <span class="nav-lab">Upload</span>
        </button>
        <button class="nav-btn" id="navChats" aria-label="Chats" data-view="chats" aria-current="false" onclick="setView('chats')">
          <span class="nav-ico">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a8 8 0 0 1-8 8H5l-2 2V12a8 8 0 0 1 8-8h2a8 8 0 0 1 8 8z"/><path d="M9 11h6M9 14h4"/></svg>
            <span class="nav-badge" id="navChatsBadge">0</span>
          </span>
          <span class="nav-lab">Chats</span>
        </button>
        <button class="nav-btn" id="navHistory" aria-label="History" data-view="history" aria-current="false" onclick="setView('history')">
          <span class="nav-ico"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></svg></span>
          <span class="nav-lab">History</span>
        </button>
        <button class="nav-btn" id="navDetails" aria-label="Details" data-view="details" aria-current="false" onclick="setView('details')">
          <span class="nav-ico"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg></span>
          <span class="nav-lab">Details</span>
        </button>
        <button class="nav-btn" id="railToggle" aria-label="Collapse or expand the menu" title="Collapse menu" onclick="toggleRail()" style="margin-top:auto">
          <span class="nav-ico"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m14 7-5 5 5 5"/></svg></span>
          <span class="nav-lab">Collapse</span>
        </button>
      </nav>

      <!-- ===== Upload view: drop files here and choose who receives them ===== -->
      <section class="view" id="viewUpload">
      <aside class="pane-files upload-pane" id="paneFiles" aria-label="Upload files">
        <div class="brand">
          <div class="brand-mark">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v10"/><path d="m8 9 4 4 4-4"/><path d="M5 19h14"/></svg>
          </div>
          <div>
            <div class="brand-name">Pulse<em>Drop</em></div>
            <div class="brand-sub" id="brandSub">Connected to ${safeDeviceName}</div>
          </div>
        </div>

        <div class="up-head">
          <div class="up-title">Upload</div>
          <div class="up-sub">Drop files or folders anywhere on this page. They are hosted on this device right away - pick a device below to push them out.</div>
        </div>

        <div id="dropzone" class="dropzone dropzone-lg" role="button" tabindex="0" aria-label="Drop files here or press Enter to choose files">
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

        <div class="up-field">
          <div class="field-label">Send uploads to</div>
          <select class="select" id="targetSelect" aria-label="Choose which device receives the files" onchange="saveTarget()">
            <option value="">Everyone on the network</option>
          </select>
          <div class="up-hint">Pick a paired device to send straight into its inbox, or leave it on everyone - the phone pushes to every device you have paired.</div>
        </div>

        <div class="up-field">
          <div class="field-label">On this network</div>
          <div class="dev-chips" id="uploadDevChips">Checking&hellip;</div>
          <div class="up-hint">
            Every device with this page open shows up here and can be messaged from Chats.
            <button type="button" class="link-btn" onclick="setView('details')">See who is active</button>
          </div>
        </div>

        <div class="side-foot">
          <div class="privacy-note">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3 4 6v5c0 5 3.4 8.6 8 10 4.6-1.4 8-5 8-10V6z"/><path d="m9 12 2 2 4-4"/></svg>
            Everything stays on this Wi-Fi network. No cloud, no accounts, no tracking.
          </div>
        </div>
      </aside>
      </section>

      <!-- ===== Files view: everything hosted on this device, full width ===== -->
      <section class="view active" id="viewFiles">
      <div class="stage">
        <div class="stage-head">
          <div>
            <div class="stage-title">Hosted files</div>
            <div class="stage-sub" id="filesSub">Everything you drop is live on this server instantly.</div>
          </div>
          <div class="spacer"></div>
          <div>
            <div class="field-label" style="margin-bottom:4px">Layout</div>
            <div class="seg" role="group" aria-label="File layout">
              <button id="layoutGrid" class="active" onclick="setLayout('grid')">Grid</button>
              <button id="layoutList" onclick="setLayout('list')">List</button>
            </div>
          </div>
        </div>
        <div class="filter-bar">
          <div class="search">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
            <input id="searchInput" type="text" placeholder="Search files… (Ctrl+K)" aria-label="Search files" oninput="applyFilter()" />
          </div>
          <div class="chips" id="chips">
            <button class="chip active" data-cat="all" aria-label="Show all files">All</button>
            <button class="chip" data-cat="docs" aria-label="Show documents">Documents</button>
            <button class="chip" data-cat="images" aria-label="Show images">Images</button>
            <button class="chip" data-cat="videos" aria-label="Show videos">Videos</button>
            <button class="chip" data-cat="audio" aria-label="Show audio">Audio</button>
            <button class="chip" data-cat="archives" aria-label="Show archives">Archives</button>
            <button class="chip" id="receivedChip" aria-pressed="false" aria-label="Filter to files received on the phone">On phone only</button>
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
            <div class="new-peers" id="newPeerList"></div>
            <input id="newIpInput" type="text" inputmode="decimal" placeholder="…or type an address" aria-label="Device IP address" onkeydown="if(event.key==='Enter')startIpThread()" />
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
            <div class="reply-bar" id="replyBar" role="status">
              <div class="rb-body">
                <div class="rb-who" id="replyBarWho">Replying</div>
                <div class="rb-text" id="replyBarText"></div>
              </div>
              <button class="icon-round" id="replyBarCancel" aria-label="Cancel reply" onclick="cancelReply()">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>
              </button>
            </div>
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
              <h3>This device</h3>
              <div class="self-name-row">
                <input id="selfNameInput" type="text" maxlength="40" aria-label="Name shown to the phone" placeholder="Chrome on Windows" />
                <button class="btn btn-filled" onclick="applySelfName()">Save</button>
              </div>
              <div class="self-name-hint">The phone lists every browser pointed at it as a device. This is the name it shows, and the name your messages arrive under.</div>
            </div>
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
<button class="tab-btn" id="tabUpload" role="tab" aria-current="false" aria-label="Show upload" onclick="setView('upload')">
        <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 16V4"/><path d="m7 9 5-5 5 5"/><path d="M4 20h16"/></svg>
        Upload
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

  <!-- Call: full-page stage -->
  <div id="callScreen" class="call-screen" role="dialog" aria-modal="true" aria-label="Call">
    <div class="call-stage" id="callStage">
      <div class="feed-slot big" id="slotRemote">
        <img id="callRemote" class="call-remote" alt="Remote video" />
        <div class="pip-hint" aria-hidden="true">tap to swap</div>
      </div>
      <div class="feed-slot small call-pip" id="callPip" style="display: none">
        <video id="callSelf" class="self-cam" autoplay playsinline muted></video>
        <button class="pip-flip" id="callFlip" title="Switch camera" aria-label="Switch camera" onclick="flipCallCamera()">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M8 4h9a4 4 0 0 1 0 8H8"/><path d="M16 2l2 2-2 2"/><path d="M16 20H8a4 4 0 0 1 0-8h8"/><path d="M8 18l-2 2 2 2"/></svg>
        </button>
        <div class="call-pip-off" id="callPipOff">Camera off</div>
        <div class="pip-hint" aria-hidden="true">tap to swap</div>
      </div>
      <div class="call-poster" id="callPoster">
        <div class="call-avatar" id="callAvatar">B</div>
        <div class="call-peer" id="callPeer">Device</div>
        <div class="call-status" id="callStatus">Ringing on the other device</div>
      </div>
      <div class="call-top">
        <div>
          <div class="call-kicker" id="callKind">Voice call</div>
          <div class="call-name" id="callTitle">Calling</div>
          <div class="call-status" id="callYou" style="position:static;text-align:left;margin-top:5px">You</div>
        </div>
        <div class="call-clock" id="callTimer" style="display: none">0:00</div>
      </div>
      <div class="call-tag" id="callShareTag">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8"/></svg>
        <span id="callShareTxt">Sharing screen</span>
      </div>
      <div class="call-hint" id="callHint"></div>
    </div>
    <div class="call-dock">
      <div class="dock-cell" id="cellAccept" style="display: none">
        <button class="dock-btn good" id="callAccept" aria-label="Answer call" title="Answer" onclick="acceptCall()">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-17.6-17.6A2 2 0 0 1 4.2 2h3a2 2 0 0 1 2 1.7l.6 3a2 2 0 0 1-.6 1.9L7.6 9.9a16 16 0 0 0 6.5 6.5l1.5-1.5a2 2 0 0 1 1.9-.6l3 .6a2 2 0 0 1 1.5 2Z"/></svg>
        </button>
        <span class="dock-lab">Answer</span>
      </div>
      <div class="dock-cell" id="cellDecline" style="display: none">
        <button class="dock-btn danger" id="callDecline" aria-label="Decline call" title="Decline" onclick="declineCall()">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-17.6-17.6A2 2 0 0 1 4.2 2h3a2 2 0 0 1 2 1.7l.6 3a2 2 0 0 1-.6 1.9L7.6 9.9a16 16 0 0 0 6.5 6.5l1.5-1.5a2 2 0 0 1 1.9-.6l3 .6a2 2 0 0 1 1.5 2Z" transform="rotate(135 12 12)"/></svg>
        </button>
        <span class="dock-lab" id="callDeclineLab">Decline</span>
      </div>
      <div class="dock-cell" id="cellMute">
        <button class="dock-btn" id="callMute" aria-label="Mute microphone" title="Mute" onclick="toggleCallMute()">
          <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="2" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0"/><path d="M12 18v3"/><path class="sl" d="M4 3l16 18"/></svg>
        </button>
        <span class="dock-lab" id="callMuteLab">Mute</span>
      </div>
      <div class="dock-cell" id="cellCam">
        <button class="dock-btn" id="callCam" aria-label="Turn camera on or off" title="Camera" onclick="toggleCallCamera()">
          <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m23 7-7 5 7 5V7Z"/><rect x="1" y="5" width="15" height="14" rx="3"/><path class="sl" d="M4 3l16 18"/></svg>
        </button>
        <span class="dock-lab" id="callCamLab">Camera</span>
      </div>
      <div class="dock-cell" id="cellShare">
        <button class="dock-btn" id="callShareBtn" aria-label="Share your screen" title="Share screen" onclick="toggleCallShare()">
          <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/></svg>
        </button>
        <span class="dock-lab" id="callShareLab">Share</span>
      </div>
      <div class="dock-cell" id="cellEnd">
        <button class="dock-btn danger" id="callEnd" aria-label="End call" title="End call" onclick="endCall()">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-17.6-17.6A2 2 0 0 1 4.2 2h3a2 2 0 0 1 2 1.7l.6 3a2 2 0 0 1-.6 1.9L7.6 9.9a16 16 0 0 0 6.5 6.5l1.5-1.5a2 2 0 0 1 1.9-.6l3 .6a2 2 0 0 1 1.5 2Z" transform="rotate(135 12 12)"/></svg>
        </button>
        <span class="dock-lab" id="callEndLab">End</span>
      </div>
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
    /* This tab's identity on the phone: the browser has no listener to dial, so the only
       way it joins the mesh is by announcing itself on every request back to the server. */
    function defaultClientName() {
      var ua = navigator.userAgent || '';
      var br = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox'
        : /CriOS\//.test(ua) ? 'Chrome' : /Chrome\//.test(ua) ? 'Chrome'
        : /Safari\//.test(ua) ? 'Safari' : 'Browser';
      var os = /Windows/.test(ua) ? 'Windows' : /Android/.test(ua) ? 'Android'
        : /iPhone|iPad|iPod/.test(ua) ? 'iOS' : /Mac OS X/.test(ua) ? 'macOS'
        : /CrOS/.test(ua) ? 'Chromebook' : /Linux|X11/.test(ua) ? 'Linux' : 'Device';
      return br + ' on ' + os;
    }
    var MY_NAME = localStorage.getItem('pulse_client_name') || defaultClientName();
    function setClientName(n) {
      var v = String(n || '').trim().slice(0, 40);
      if (!v) return;
      MY_NAME = v;
      try { localStorage.setItem('pulse_client_name', v); } catch (e) {}
      var i = el('selfNameInput');
      if (i) i.value = v;
    }
    function applySelfName() {
      var i = el('selfNameInput');
      var v = (i.value || '').trim();
      if (!v) { toast('Give the device a name first'); return; }
      setClientName(v);
      toast('You now show up as "' + MY_NAME + '" on the phone');
    }
    var myIp = '';
    var BOOT_TIME = Date.now();

    var allFiles = [];
    var lastMsgs = [];
    /* The whole-history read, serialised: one in flight, and one more queued if anything asked during it. */
    var messagesInFlight = false;
    var messagesWanted = false;
    // The fingerprint of the last body this tab fully read, so an unchanged room costs nothing.
    var messagesEtag = '';
    // The phone's place-marker for this tab's view of the room. Held here rather than left to a
    // cookie or cache: the room's JSON is `no-store` on purpose, so nothing is stored to carry it.
    var roomCursor = '';
    /* Messages this tab was not allowed to retract, plus a revision that says "redraw the bubbles". */
    var localHidden = {};
    var chatRev = 0;
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
      // Lift the toast clear of the call dock while a call owns the bottom of the screen.
      t.classList.toggle('in-call', typeof call !== 'undefined' && !!call && !!call.cid);
      t.classList.add('show');
      clearTimeout(t._t);
      t._t = setTimeout(function () { t.classList.remove('show'); }, 2600);
    }

    function authHeaders() {
      var h = {};
      if (authToken) h['X-Pulse-Auth'] = authToken;
      h['X-Pulse-Client'] = encodeURIComponent(MY_NAME);
      return h;
    }

    /* ---- URL tickets ----
       `<img>`, `<a download>` and EventSource cannot attach a header, so they carry a ticket in the
       URL instead: it names exactly one object, works only from this address, and dies in minutes.
       The session token that used to sit in these URLs was good for twelve hours. */
    var ticketCache = {};
    function ticketScopeOf(url) {
      var path = String(url || '').split('?')[0];
      var q = String(url || '').indexOf('?') >= 0 ? String(url).slice(String(url).indexOf('?') + 1) : '';
      if (path === '/download-folder') {
        var at = q.indexOf('path=');
        var raw = at >= 0 ? q.slice(at + 5).split('&')[0] : '';
        try { raw = decodeURIComponent(raw); } catch (e) {}
        return 'folder:' + raw;
      }
      if (path.indexOf('/download/') === 0) return 'download:' + decodeURIComponent(path.slice(10));
      if (path.indexOf('/chatfile/') === 0) return 'chatfile:' + decodeURIComponent(path.slice(10));
      if (path.indexOf('/preview/') === 0) return 'preview:' + decodeURIComponent(path.slice(9));
      return null;
    }
    function ticketFor(scope) {
      var now = Date.now();
      var hit = ticketCache[scope];
      if (hit && hit.until > now + 15000) return Promise.resolve(hit.ticket);
      return fetch('/api/ticket', {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ scope: scope })
      }).then(function (res) { return res.ok ? res.json() : null; })
        .then(function (out) {
          if (!out || !out.ticket) return null;
          // A little inside the phone's five minutes, so a URL handed over a beat late still works.
          ticketCache[scope] = { ticket: out.ticket, until: now + 210000 };
          return out.ticket;
        })
        .catch(function () { return null; });
    }
    function withTicket(url, scope) {
      if (!scope) return Promise.resolve(url);
      return ticketFor(scope).then(function (t) {
        return t ? url + (url.indexOf('?') >= 0 ? '&' : '?') + 'ticket=' + encodeURIComponent(t) : url;
      });
    }
    function setMediaUrl(node, url) {
      var scope = ticketScopeOf(url);
      if (!scope || !authToken) { node.src = url; return; }
      withTicket(url, scope).then(function (u) { node.src = u; });
    }
    /* One listener instead of every download link learning about tickets: the href already names
       the object, and that is the only thing a ticket is scoped to. */
    document.addEventListener('click', function (e) {
      if (!e.target || !e.target.closest) return;
      var a = e.target.closest('a[href]');
      if (!a || !authToken) return;
      var href = a.getAttribute('href') || '';
      // The anchor this handler creates already carries its ticket; intercepting it again would
      // mint in a loop.
      if (href.indexOf('ticket=') >= 0) return;
      var scope = ticketScopeOf(href);
      if (!scope) return;
      e.preventDefault();
      withTicket(href, scope).then(function (url) {
        var go = document.createElement('a');
        go.href = url;
        var dl = a.getAttribute('download');
        if (dl !== null) go.setAttribute('download', dl);
        document.body.appendChild(go);
        go.click();
        go.remove();
      });
    });

    function revealApp() {
      el('appRoot').style.display = 'flex';
      var i = el('selfNameInput');
      if (i && !i.value) i.value = MY_NAME;
    }

    function lockOut() {
      // Tell the phone before forgetting the token, so a closed tab does not leave a session
      // sitting in its dashboard until the 12-hour expiry. Best effort: a 401 already proved the
      // credential is dead, and losing this call must not trap the user in a locked UI.
      if (authToken) {
        try {
          fetch('/api/auth/logout', {
            method: 'POST',
            keepalive: true,
            headers: { 'X-Pulse-Auth': authToken }
          }).catch(function () {});
        } catch (e) {}
      }
      localStorage.removeItem('pulse_token');
      authToken = '';
      // The next login answers a different room than the one this validator described.
      messagesEtag = '';
      // And a different room than the cursor points into, so the first read after login is whole.
      roomCursor = '';
      // A dead session cannot mint a ticket, so stop reconnecting until a new login succeeds.
      eventsStopped = true;
      try { if (eventsStream) eventsStream.close(); } catch (e) {}
      // Tickets outlive the session that minted them by a few minutes. That is fine on the phone
      // and wrong here: the tab would spend an invalid one before checking.
      ticketCache = {};
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
          headers: { 'Content-Type': 'application/json; charset=utf-8' },
          body: JSON.stringify({ pin: pin })
        });
        var data = await res.json();
        if (data.success) {
          // The session, never the PIN: this value is what a tab keeps, and it used to be the room
          // secret itself. A response without one means a server that has no session to give, so the
          // tab must not fall back to caching the typed PIN.
          if (!data.token) {
            el('pinError').textContent = 'The phone returned no session. Reload the page and try again.';
            el('pinError').style.display = 'block';
            return;
          }
          authToken = data.token;
          localStorage.setItem('pulse_token', authToken);
          el('authOverlay').style.display = 'none';
          revealApp();
          fetchFiles();
          fetchInfo();
          fetchMessages();
          // The stream was stopped when the old session died; a fresh one can mint a fresh ticket.
          eventsStopped = false;
          connectEvents();
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
    /* Every section is addressable: #/files, #/chats… so a link, a reload or the Back
       button lands on the same screen instead of always reopening Files. */
    var VIEW_IDS = { files: 1, upload: 1, chats: 1, history: 1, details: 1 };
    var syncingHash = false;
    function viewFromHash() {
      var v = String(location.hash || '').replace(/^#\/?/, '').split(/[?#]/)[0];
      return VIEW_IDS[v] ? v : 'files';
    }
    // null: follow the page (Chats folds the rail); true/false: the visitor pinned it by hand.
    var railPinned = null;
    try { var rp = localStorage.getItem('pulseRailPin'); if (rp === '1' || rp === '0') railPinned = rp === '1'; } catch (e) {}
    function applyRailMode(view) {
      var rail = el('navRail');
      if (!rail) return;
      var mini = railPinned === null ? view === 'chats' : railPinned;
      rail.classList.toggle('mini', mini);
      var btn = el('railToggle');
      if (btn) btn.title = mini ? 'Expand menu' : 'Collapse menu';
    }
    function toggleRail() {
      var rail = el('navRail');
      if (!rail) return;
      railPinned = !rail.classList.contains('mini');
      try { localStorage.setItem('pulseRailPin', railPinned ? '1' : '0'); } catch (e) {}
      applyRailMode(currentView);
    }

    function setView(view, fromHash) {
      if (!VIEW_IDS[view]) view = 'files';
      currentView = view;
      if (!fromHash) {
        try { history.replaceState(null, '', '#/' + view); } catch (e) { /* file: or blocked */ }
      }
      applyRailMode(view);
      var map = { files: 'viewFiles', upload: 'viewUpload', chats: 'paneChat', history: 'viewHistory', details: 'viewDetails' };
      var tabs = { files: 'tabFiles', upload: 'tabUpload', chats: 'tabChat', history: 'tabHistory', details: 'tabDetails' };
      var navs = { files: 'navFiles', upload: 'navUpload', chats: 'navChats', history: 'navHistory', details: 'navDetails' };
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
    window.addEventListener('hashchange', function () {
      var v = viewFromHash();
      if (v !== currentView) setView(v, true);
    });

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
        renderMyIdentity();
        if (info.version) el('brandSub').textContent = 'Connected to ' + info.deviceName + ' - v' + info.version;
        noteSecureAvailability(info);
      } catch (e) {}
    }

    /* ===== Getting an http tab onto the secure origin =====
       A tab on plain can see and hear but cannot send a mic, camera or screen, and the phone's
       https port may bind a moment after the page loaded. So the port's arrival is polled, and the
       tab offers the jump rather than only explaining the limitation. */
    var tlsPortInfo = 0, tlsUpInfo = false, tlsDownReason = '', tlsSaid = false, tlsProbed = false;
    var secureOffered = false, needsSecure = false;

    function noteSecureAvailability(info) {
      if (info.tlsPort) tlsPortInfo = info.tlsPort;
      tlsProbed = true;
      tlsUpInfo = !!info.tlsUp;
      tlsDownReason = info.tlsError || '';
      if (info.secure) { el('secureBtn').style.display = 'none'; return; }
      if (!info.tlsUp) {
        // The phone could not start its secure port at all. Polling on silently, with a tab that
        // cannot send a camera and no explanation anywhere, is how "the browser just says not
        // secure" becomes a dead end. Say it once, then keep asking in the background — the port
        // can come up on a later retry, and `secureOffered` must stay false so this tab's poll
        // keeps running.
        if (!tlsSaid && tlsDownReason) {
          tlsSaid = true;
          toast('This phone\'s secure port is not running: ' + tlsDownReason +
                ' - calls can still be received here');
        }
        return;
      }
      if (!secureOffered) {
        secureOffered = true;
        el('secureBtn').style.display = 'inline-flex';
        toast('Secure mode is ready - full calls need one tap on it');
      }
      // Someone already tried to use the camera or screen and was refused: move them across
      // on their own, but never yank a tab that is mid-call or mid-upload.
      if (needsSecure && !live.busy && !uploading) goSecure();
    }

    /* A refused camera has two opposite causes - this tab has not moved to the phone's https
       port, or that port never started - so name which one before advising anything. */
    function secureAdvice() {
      if (!tlsProbed) return 'Checking whether this phone has a secure port…';
      if (tlsUpInfo) return 'Tap "Secure mode" above, then accept the certificate warning once.';
      var reason = tlsDownReason ? ' (' + tlsDownReason + ')' : '';
      return 'This phone\'s https port is not running' + reason + '. Chat and file transfer work ' +
             'here, but a browser will not hand over a camera to an http page. To send video from ' +
             'Chrome or Edge anyway: tap the address button above to copy ' + location.origin +
             ', open chrome://flags, set "Unsafely treat insecure origin as secure" to that ' +
             'address, restart the browser and reload this page.';
    }

    function goSecure() {
      if (location.protocol === 'https:') return;
      var host = location.hostname;
      var port = tlsPortInfo || (parseInt(location.port || '8080', 10) + 1);
      needsSecure = true;
      el('secureBtn').classList.add('spin');
      toast('Opening the secure address - accept the certificate warning, it is this phone');
      setTimeout(function () { location.replace('https://' + host + ':' + port + '/'); }, 500);
    }

    function renderMyIdentity() {
      var chip = el('meChip'), txt = el('meChipTxt');
      if (!txt) return;
      txt.textContent = '';
      if (!myIp) { chip.style.display = 'none'; return; }
      txt.appendChild(document.createTextNode('You '));
      var b = document.createElement('b');
      b.textContent = myIp;
      txt.appendChild(b);
      chip.onclick = function () { copyToClipboard(myIp); };
    }

    /* ===== Manual refresh ===== */
    async function manualRefresh() {
      var btn = el('refreshBtn');
      btn.classList.add('spin');
      try {
        await Promise.all([fetchFiles(), fetchPeers(), fetchInfo(), fetchThreads(), fetchMessages()]);
        toast('Up to date');
      } catch (e) {
        toast('Could not reach the phone - try again');
      }
      setTimeout(function () { btn.classList.remove('spin'); }, 450);
    }

    function setConnected(ok) {
      var pill = el('connPill');
      el('connText').textContent = ok ? 'Connected' : 'Reconnecting…';
      pill.classList.toggle('off', !ok);
    }

    function updateStats() {
      var total = allFiles.reduce(function (s, f) { return s + (f.size || 0); }, 0);
      var sub = el('filesSub');
      if (sub) {
        sub.textContent = allFiles.length
          ? allFiles.length + ' hosted - ' + formatBytes(total) + ' - live on this network'
          : 'Nothing hosted yet. Drop a file and it is live instantly.';
      }
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
      // "On phone only" lives in this row but is a toggle, not a category; taking the branch
      // for categories would set currentCat to undefined and blank the list.
      if (!chip || !chip.dataset.cat) return;
      document.querySelectorAll('#chips .chip[data-cat]').forEach(function (c) { c.classList.remove('active'); });
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

    var FE_UP = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M5 12l7-7 7 7"/></svg>';
    var FE_CHAT = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a8 8 0 0 1-8 8H8l-4 3v-6.5A8 8 0 0 1 12 4h1a8 8 0 0 1 8 8Z"/></svg>';

    function feBtn(cls, svg, label, run) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'fe-btn ' + cls;
      b.innerHTML = svg;
      b.appendChild(document.createElement('span')).textContent = label;
      b.onclick = run;
      return b;
    }

    function feHints(items) {
      var h = document.createElement('div');
      h.className = 'fe-hints';
      items.forEach(function (t) {
        var s = document.createElement('span');
        s.className = 'fe-hint';
        s.appendChild(document.createElement('i'));
        s.appendChild(document.createTextNode(t));
        h.appendChild(s);
      });
      return h;
    }

    function emptyShelfNode() {
      var w = document.createElement('div');
      w.className = 'files-empty';
      var art = document.createElement('div');
      art.className = 'fe-art';
      art.setAttribute('aria-hidden', 'true');
      art.innerHTML = '<div class="fe-card c1"></div><div class="fe-card c2"></div>' +
        '<div class="fe-card c3"></div><div class="fe-plus">' +
        '<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg></div>';
      var t = document.createElement('div');
      t.className = 'fe-title';
      t.textContent = 'Nothing hosted yet';
      var s = document.createElement('div');
      s.className = 'fe-sub';
      s.textContent = 'Photos, documents, builds — anything at all. It lands on this phone’s server the moment you drop it, and every device on the network can pull it from there.';
      var c = document.createElement('div');
      c.className = 'fe-ctas';
      c.appendChild(feBtn('primary', FE_UP, 'Upload files', pickFiles));
      c.appendChild(feBtn('ghost', FE_CHAT, 'Message a device', function () { setView('chats'); }));
      w.appendChild(art); w.appendChild(t); w.appendChild(s); w.appendChild(c);
      w.appendChild(feHints(['No cloud, no account', 'Only this Wi-Fi network', 'Drop files anywhere on the page']));
      return w;
    }

    function emptyFilterNode() {
      var w = document.createElement('div');
      w.className = 'files-empty';
      var art = document.createElement('div');
      art.className = 'fe-art';
      art.setAttribute('aria-hidden', 'true');
      art.innerHTML = '<div class="fe-card c1"></div><div class="fe-card c2"></div><div class="fe-card c3"></div>';
      var t = document.createElement('div');
      t.className = 'fe-title';
      t.textContent = 'Nothing matches';
      var s = document.createElement('div');
      s.className = 'fe-sub';
      s.textContent = allFiles.length + ' file' + (allFiles.length === 1 ? '' : 's') + ' hosted here, none of them fit this filter.';
      var c = document.createElement('div');
      c.className = 'fe-ctas';
      c.appendChild(feBtn('primary', FE_UP, 'Show everything', function () {
        currentCat = 'all';
        receivedOnly = false;
        el('searchInput').value = '';
        document.querySelectorAll('#chips .chip').forEach(function (x) {
          x.classList.toggle('active', x.dataset.cat === 'all');
        });
        el('receivedChip').classList.remove('active');
        el('receivedChip').setAttribute('aria-pressed', 'false');
        applyFilter();
      }));
      w.appendChild(art); w.appendChild(t); w.appendChild(s); w.appendChild(c);
      return w;
    }

    function renderEmpty(grid, message, action) {
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
      if (action) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'empty-cta';
        btn.textContent = action.label;
        btn.onclick = action.run;
        wrap.appendChild(btn);
      }
      grid.appendChild(wrap);
    }

    function renderFiles(list) {
      var grid = el('filesGrid');
      grid.textContent = '';
      if (list.length === 0) {
        grid.appendChild(allFiles.length === 0 ? emptyShelfNode() : emptyFilterNode());
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
      // No credential in the URL. `/download/{id}` is scoped to the address that uploaded or was
      // sent the file, and a session token in an href is copied into history and into the next
      // page's referrer, where nothing needs it.
      return '/download/' + encodeURIComponent(id);
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
        headers['Content-Type'] = 'application/json; charset=utf-8';
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
    /* The phone's PulseAvatarPalette, kept in step with it: the one place a contact may be a colour
       rather than a theme token, and each entry is dark enough that white initials clear AA. A
       gradient would put two hues under the same pair of letters, so only the darker end counted. */
    var AV_COLORS = ['#2563EB', '#7C3AED', '#0F766E', '#15803D', '#B45309', '#BE123C'];

    function hashOf(s) {
      var h = 0;
      for (var i = 0; i < s.length; i++) { h = (h * 31 + s.charCodeAt(i)) | 0; }
      return Math.abs(h);
    }
    function avColor(seed) { return AV_COLORS[hashOf(String(seed)) % AV_COLORS.length]; }
    function initialsOf(name) {
      var parts = String(name || '?').trim().split(/[\s_-]+/).filter(Boolean);
      if (!parts.length) return '?';
      // "Firefox on Linux" is FL, not FO — a joiner in the middle of a display name is not a name.
      var joiners = { on: 1, of: 1, the: 1, and: 1, de: 1, la: 1, le: 1 };
      var first = parts[0].charAt(0);
      var rest = parts.slice(1).filter(function (w) { return !joiners[w.toLowerCase()]; });
      var second = rest.length ? rest[0].charAt(0) : (parts.length === 1 ? parts[0].charAt(1) : '');
      var out = (first + second).toUpperCase();
      return out || '?';
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
        var known = peersList.filter(function (p) { return p.ip === ip && p.name && p.name !== ip; });
        if (known.length) return known[0].name;
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
      // The rail is for direct conversations; group threads belong to the room.
      Object.keys(seen).forEach(function (t) { if (t.indexOf('group:') === 0) delete seen[t]; });
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
      // One read at a time. Every message that arrives pushes a doorbell on the event stream, and a
      // burst of ten used to start ten concurrent whole-history GETs in this tab — each transferred,
      // parsed and re-rendered, while the 2 s poll kept firing behind them. The trailing re-run is
      // what keeps the guard from becoming a missed update: whatever arrived during the read is
      // fetched again, so the last word is never the one that got dropped.
      if (messagesInFlight) { messagesWanted = true; return; }
      messagesInFlight = true;
      try {
        var headers = authHeaders();
        // Offered as a header rather than left to the browser cache: JSON answers carry no-store
        // on purpose, so nothing is stored to revalidate, but the copy this tab is holding is
        // exactly the body that fingerprint was taken over.
        if (messagesEtag) headers['if-none-match'] = messagesEtag;
        // With a cursor the phone sends only what moved, so one new message costs one message and
        // not the room's whole life. Without one — first read, or a cursor the phone refused — this
        // is exactly the whole-history read it has always been.
        var url = roomCursor ? '/api/messages?since=' + encodeURIComponent(roomCursor) : '/api/messages';
        var res = await fetch(url, { headers: headers });
        if (res.status === 401 && REQUIRES_PIN) { lockOut(); return; }
        // 304 is not `ok` to fetch, and treating it as a failure would drop the tab offline every
        // time the phone agreed nothing had changed.
        if (res.status === 304) { setConnected(true); }
        else if (!res.ok) { setConnected(false); return; }
        else {
          setConnected(true);
          messagesEtag = res.headers.get('etag') || '';
          roomCursor = res.headers.get('x-room-cursor') || '';
          var msgs = await res.json();
          processMessages(msgs, res.headers.get('x-room-delta') === '1');
        }
      } catch (e) { setConnected(false); }
      finally {
        messagesInFlight = false;
        if (messagesWanted && !(REQUIRES_PIN && !authToken)) { messagesWanted = false; fetchMessages(); }
      }
      fetchTyping();
    }

    /**
     * Fold one patch into the room this tab is holding.
     *
     * A patch is only ever "these entries moved since the marker you sent", so the tab is the other
     * half of the list. A retraction replaces the message where it stands, an arrival is appended,
     * and nothing is re-sorted: the phone sends oldest-first, so appending keeps the order the
     * bubbles are already in.
     *
     * The one thing this costs is honesty about retention: a message the phone has dropped off its
     * 2000 cap stays on this screen until the room is read whole again, which happens on a reload, a
     * logout, or the phone opening or closing a thread. Reading the room as a replacement to avoid
     * that would be the 517 KB this exists to stop sending.
     */
    function mergeRoomPatch(msgs) {
      if (!msgs.length) return;
      var at = {};
      lastMsgs.forEach(function (m, i) { at[m.id] = i; });
      msgs.forEach(function (m) {
        if (at[m.id] !== undefined) { lastMsgs[at[m.id]] = m; }
        else { at[m.id] = lastMsgs.length; lastMsgs.push(m); }
      });
    }

    function processMessages(msgs, isDelta) {
      // A patch joins what this tab holds; a whole room replaces it. Reading a patch as a replacement
      // would leave the tab showing only the messages that moved, which is the bug this route's
      // own history taught: the answer's shape is not enough, the reader has to know which it got.
      if (isDelta) mergeRoomPatch(msgs); else lastMsgs = msgs;

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
            // A tab in the background is a phone in a pocket: say so in the shade.
            if (document.hidden) messageAlert(m, t);
          }
        }
        lastSeenTs[t] = Math.max(lastSeenTs[t] || 0, ts);
      });
      if (!chatInited) chatInited = true;

      updateStats();
      renderThreadRail();
      paintTitleCount();
      renderChat(false);
    }

    /* Unread count in the tab title, where a backgrounded tab is actually seen. */
    function paintTitleCount() {
      var total = unreadTotal();
      try {
        var base = ring && ring.title ? ring.title : document.title.replace(/^\((\d+)\) /, '');
        if (total > 0 && document.hidden) document.title = '(' + (total > 99 ? '99+' : total) + ') ' + base;
        else if (document.title !== base && !(ring && ring.ticker)) document.title = base;
      } catch (e) {}
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
      av.style.background = avColor(row.id || 'everyone');
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
      av.style.background = avColor(t || 'everyone');
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
      if (open) { renderNewChatPeers(); el('newIpInput').focus(); fetchPeers(); }
    }

    // Every device that is on this server — phone app or browser tab — is one click away.
    function renderNewChatPeers() {
      var host = el('newPeerList');
      if (!host) return;
      host.textContent = '';
      var others = peersList.filter(function (p) { return p.ip && p.ip !== myIp; });
      if (!others.length) {
        var empty = document.createElement('div');
        empty.className = 'new-peers-empty';
        empty.textContent = 'No other device is on this server yet. Type its address below.';
        host.appendChild(empty);
        return;
      }
      others.forEach(function (p) {
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'new-peer' + (p.active ? '' : ' idle') + (currentThread === 'ip:' + p.ip ? ' on' : '');
        b.textContent = p.name + ' · ' + p.ip + (p.kind === 'browser' ? ' (browser)' : '') + (p.active ? '' : ' · idle');
        b.title = p.active ? 'Active now' : 'Last seen ' + agoText(p.lastSeen);
        b.onclick = function () {
          el('railNew').classList.remove('open');
          threadsSeen['ip:' + p.ip] = true;
          selectThread('ip:' + p.ip);
        };
        host.appendChild(b);
      });
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
      var list = lastMsgs.filter(function (m) { return threadOf(m) === currentThread && !localHidden[m.id]; });
      return list.slice(-200);
    }

    function renderChat(forceScroll) {
      var list = el('beamList');
      var disp = currentThreadMsgs();
      var lastId = disp.length ? String(disp[disp.length - 1].id || '') : '';
      // A delete changes neither the count nor the last id — the row stays and only its words leave.
      // chatRev covers the tab that did the deleting, but a tab that learns about it from the poll
      // (its event stream dropped) has nothing to notice, so the tombstones are counted too.
      var tombstones = 0;
      for (var i = 0; i < disp.length; i++) if (disp[i].deleted) tombstones++;
      var fp = disp.length + '|' + lastId + '|' + currentThread + '|' + chatRev + '|' + tombstones;
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

      if (m.deleted) {
        /* The bubble stays so the thread keeps its shape for anybody already reading it. */
        var gone = document.createElement('div');
        gone.className = 'bubble deleted';
        gone.textContent = 'This message was deleted';
        col.appendChild(gone);
      } else {
        if (m.replyTo) col.appendChild(buildQuote(m));
        if (m.attachment) col.appendChild(buildAttachmentCard(m.attachment));
        if (String(m.text || '') !== '') {
          var bub = document.createElement('div');
          bub.className = 'bubble';
          bub.textContent = m.text;
          col.appendChild(bub);
        }
      }

      var meta = document.createElement('div');
      meta.className = 'bubble-meta';
      meta.style.minHeight = isLatest ? '' : '0';
      meta.style.display = 'flex';
      if (isLatest && !m.deleted) {
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
        cp.addEventListener('click', function () { copyToClipboard(String(m.text || (m.attachment && m.attachment.name) || '')); });
        meta.appendChild(cp);

        var acts = document.createElement('span');
        acts.className = 'bubble-actions';
        acts.appendChild(bubbleAction('Reply', 'Reply to this message', function () { startReply(m); }));
        acts.appendChild(bubbleAction(
          m.mine ? 'Delete' : 'Delete here',
          m.mine ? 'Delete this message for everyone' : 'Remove this message from this tab only',
          function () { deleteMessage(m); },
          true
        ));
        meta.appendChild(acts);
      } else if (m.deleted) {
        var acts2 = document.createElement('span');
        acts2.className = 'bubble-actions';
        acts2.appendChild(bubbleAction('Reply', 'Reply to this message', function () { startReply(m); }));
        meta.appendChild(acts2);
      } else {
        meta.style.visibility = 'hidden';
        meta.textContent = '.';
      }
      col.appendChild(meta);

      row.appendChild(col);
      return row;
    }

    function bubbleAction(label, title, onClick, danger) {
      var b = document.createElement('button');
      b.type = 'button';
      b.textContent = label;
      b.title = title;
      b.setAttribute('aria-label', title);
      if (danger) b.className = 'danger';
      b.addEventListener('click', onClick);
      return b;
    }

    /* The quote renders from the snapshot the sender attached, so it survives the original being
       deleted - and on a shared room the original may not even be in this tab's list. */
    function buildQuote(m) {
      var box = document.createElement('div');
      box.className = 'reply-quote';
      var bar = document.createElement('div');
      bar.className = 'rq-bar';
      var body = document.createElement('div');
      body.className = 'rq-body';
      var who = document.createElement('div');
      who.className = 'rq-who';
      who.textContent = String(m.replySender || 'Message');
      var text = document.createElement('div');
      text.className = 'rq-text';
      text.textContent = String(m.replyText || '');
      body.appendChild(who);
      body.appendChild(text);
      box.appendChild(bar);
      box.appendChild(body);
      return box;
    }

    var replyingTo = null;
    function startReply(m) {
      replyingTo = m;
      el('replyBarWho').textContent = 'Replying to ' + (m.deleted ? 'a deleted message' : (m.mine ? 'you' : (m.sender || 'the sender')));
      el('replyBarText').textContent = m.deleted ? '' : String(m.text || (m.attachment ? m.attachment.name : ''));
      el('replyBar').classList.add('show');
      var input = el('beamInput');
      if (input) input.focus();
    }
    function cancelReply() {
      replyingTo = null;
      el('replyBar').classList.remove('show');
      el('replyBarText').textContent = '';
    }

    function deleteMessage(m) {
      // A tab may only retract what it wrote; the phone owns the rest of the room.
      if (!m.mine) {
        localHidden[m.id] = true;
        chatRev++;
        renderChat(true);
        toast('Deleted on this device only');
        return;
      }
      var headers = authHeaders();
      // The id goes in the query: the phone's server discards a DELETE body before its handler runs.
      fetch('/api/messages?id=' + encodeURIComponent(m.id), { method: 'DELETE', headers: headers })
        .then(function (res) {
          if (res.status === 401) { lockOut(); return; }
          chatRev++;
          if (!res.ok) { toast('Could not delete that message'); return; }
          toast('Message deleted for everyone');
          fetchMessages();
        })
        .catch(function () { toast('Could not reach the phone to delete that'); });
    }

    /* ===== Chat attachments: the file itself lives in the bubble ===== */
    /* Attachment bytes are read by `<img>` and `<a>`, which cannot send a header, so the routes
       themselves authorise by address and ownership — see `chatFileIsVisibleTo` on the phone.
       Nothing here appends a credential. */
    function chatFileUrl(id, asDownload) {
      return '/chatfile/' + encodeURIComponent(id) + (asDownload ? '?dl=1' : '');
    }
    function hostedPreviewUrl(id) { return '/preview/' + encodeURIComponent(id); }

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
        setMediaUrl(im, chatFileUrl(att.id, false));
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

    /**
     * Decodes bytes the extension claimed were text, or returns null when they clearly
     * are not. A .txt that is really a compiled blob otherwise filled the lightbox with
     * replacement glyphs, which is what "mojibake" screens look like.
     */
    function decodeAsText(bytes) {
      var probe = Math.min(bytes.length, 8192);
      var nul = 0, ctrl = 0, high = 0;
      for (var i = 0; i < probe; i++) {
        var b = bytes[i];
        if (b === 0) nul++;
        else if (b < 9 || (b > 13 && b < 32)) ctrl++;
        else if (b > 127) high++;
      }
      if (!bytes.length) return '';
      if (nul > 0 || (ctrl + high) / probe > 0.12) return null;
      // UTF-16 text is dominated by 0x00 bytes even without a BOM.
      return new TextDecoder('utf-8', { fatal: false }).decode(bytes);
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
        setMediaUrl(img, it.url);
        img.addEventListener('error', function () { showLbFallback(it, 'preview failed'); });
        stage.appendChild(img);
      } else if (kind === 'videos') {
        lb.zoomable = true;
        var vid = lbShell('lb-video', 'video');
        setMediaUrl(vid, it.url);
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
        setMediaUrl(au, it.url);
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
        setMediaUrl(frame, it.url);
        frame.title = it.name;
        stage.appendChild(frame);
      } else if (TEXTY.indexOf(ext) >= 0) {
        if (it.size && it.size > 2 * 1024 * 1024) {
          showLbFallback(it, 'too large to preview');
          setZoomUi();
          return;
        }
        lb.zoomable = true;
        var scroller = lbShell('lb-text');
        var pre = document.createElement('pre');
        pre.textContent = 'Loading…';
        scroller.appendChild(pre);
        stage.appendChild(scroller);
        fetch(it.url, { headers: authHeaders() })
          .then(function (res) { if (!res.ok) throw new Error('http'); return res.arrayBuffer(); })
          .then(function (buf) {
            if (lb.node !== scroller) return;
            var bytes = new Uint8Array(buf);
            var body = decodeAsText(bytes);
            if (body === null) { showLbFallback(it, 'binary file, not text'); setZoomUi(); return; }
            pre.textContent = body.length > 400000
              ? body.slice(0, 400000) + '\n\n… truncated. Download the file to read all of it.'
              : body;
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
    // Tapping the dark area around a preview is how everyone expects it to close, and on a
    // phone the close button can sit under the browser's own chrome.
    el('lightbox').addEventListener('click', function (e) {
      if (e.target === this || e.target === el('lbStage')) closePreview();
    });

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
          headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, authHeaders()),
          body: JSON.stringify({ sender: MY_NAME, typing: true })
        }).catch(function () {});
      }
    });

    function clearTyping() {
      fetch('/api/typing', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, authHeaders()),
        body: JSON.stringify({ sender: MY_NAME, typing: false })
      }).catch(function () {});
    }

    function postThreadMessage(text, attachmentId, replyToId) {
      var headers = authHeaders();
      headers['Content-Type'] = 'application/json; charset=utf-8';
      var body = { text: text || '', sender: MY_NAME };
      if (attachmentId) body.attachmentId = attachmentId;
      if (replyToId) body.replyTo = replyToId;
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
      var quoted = replyingTo ? replyingTo.id : '';
      var ok = await postThreadMessage(text, '', quoted);
      if (ok) {
        input.value = '';
        input.style.height = 'auto';
        cancelReply();
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
        xhr.open('POST', '/chatupload?filename=' + encodeURIComponent(file.name), true);
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
        if (!p.ip || p.ip === myIp || p.self) return;
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
      if (!list.length) { renderEmpty(host, 'Nothing has moved through this server yet.', { label: 'Go to Upload', run: function () { setView('upload'); } }); return; }
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
      renderNewChatPeers();
      renderUploadDevices();
      renderSession();
    }

    function renderUploadDevices() {
      var host = el('uploadDevChips');
      if (!host) return;
      host.textContent = '';
      var others = peersList.filter(function (p) { return p.ip && p.ip !== myIp; });
      if (!others.length) {
        host.textContent = 'Just this tab right now.';
        return;
      }
      others.forEach(function (p) {
        var s = document.createElement('span');
        s.className = 'dev-chip' + (p.active ? ' live' : '');
        s.textContent = p.name + (p.active ? '' : ' · idle');
        s.title = p.active ? 'Active now' : 'Last seen ' + agoText(p.lastSeen);
        host.appendChild(s);
      });
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
      head.innerHTML = '<tr><th>Device</th><th>Address</th><th>Status</th><th>Files sent</th><th></th></tr>';
      table.appendChild(head);
      var body = document.createElement('tbody');
      peersList.forEach(function (p) {
        var tr = document.createElement('tr');
        var me = p.ip === myIp;
        [
          { c: 'who', t: (me ? p.name + ' (you)' : p.name) + (p.kind === 'browser' ? ' · browser' : '') },
          { c: 'ip', t: p.ip },
          { c: '', t: p.active ? 'Active now' : 'Last seen ' + agoText(p.lastSeen) },
          { c: '', t: (p.files || 0) + (p.bytes ? ' · ' + formatBytes(p.bytes) : '') }
        ].forEach(function (cell) {
          var td = document.createElement('td');
          td.className = cell.c;
          td.textContent = cell.t;
          tr.appendChild(td);
        });
        var act = document.createElement('td');
        if (!me) {
          var mb = document.createElement('button');
          mb.className = 'btn btn-quiet';
          mb.style.cssText = 'padding:5px 10px;font-size:11.5px';
          mb.textContent = 'Message';
          mb.onclick = function () {
            threadsSeen['ip:' + p.ip] = true;
            selectThread('ip:' + p.ip);
          };
          act.appendChild(mb);
        }
        tr.appendChild(act);
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
        ['You appear as', MY_NAME],
        ['Server address', location.href],
        ['Your address', myIp || '-'],
        [
          'Secure mode',
          window.isSecureContext
            ? 'On - camera, mic and screen sharing available'
            : 'Off - this tab can receive calls but not send camera or mic' +
              (tlsUpInfo ? ' (tap "Secure mode")'
                         : tlsDownReason ? ' (' + tlsDownReason + ')' : '')
        ],
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

    /* ===== Call: a real media leg ===== */
    // One call record lives on the phone; both ends poll it. `dir` says which end this tab is.
    var call = { cid: '', video: false, dir: 'out', state: '', startedAt: 0, tick: null, watch: null };

    // A tab cannot open the raw TCP socket the apps dial, so its frames ride this server:
    // one chunked response down, one short POST up every ~100 ms. Same framing as the socket,
    // [1b type][4b BE len][payload], so the phone never learns it is talking to a browser.
    var T_AUDIO = 1, T_CAMERA = 2, T_SCREEN = 3, T_STATE = 4;
    var live = {
      up: [], upTimer: null, vfTimer: null, downAbort: null, sending: 0,
      ac: null, cap: null, sp: null, srcNode: null, micGain: null, shareSrc: null,
      micStream: null, camStream: null,
      shareStream: null, next: 0, inRate: 48000, camOn: false, sharing: false,
      gotFrame: false, lastUrl: '', canvas: null, busy: false,
      // One capture attempt in flight, and why the last one failed: 'insecure' can be fixed by
      // the page moving to the https port, so it is worth trying again; a denial is not.
      capturing: false, upFail: '', resumeTick: null, upErr: 0
    };
    var pdMuted = false;
    var peerMuted = false;
    var pendingRestore = null;
    try { pendingRestore = JSON.parse(sessionStorage.getItem('pulseCall') || 'null'); } catch (e) {}

    /* ---- ringing a tab a human can actually notice ----
       A silent overlay in a buried tab is not a call. The tone is an oscillator gated by a slow
       square-wave oscillator, so its cadence is kept by the audio thread: the browser may still
       clamp this tab's timers to a tick a minute, but the ring keeps ringing. */
    var ring = { ac: null, nodes: [], title: document.title, ticker: null };

    function startRing() {
      stopRingAudio();
      try {
        var AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) return;
        var ac = new AC();
        if (ac.state === 'suspended' && ac.resume) ac.resume().catch(function () {});
        var master = ac.createGain(); master.gain.value = 0.16; master.connect(ac.destination);
        var gate = ac.createGain(); gate.gain.value = 0.5; gate.connect(master);
        var lfo = ac.createOscillator(); lfo.type = 'square'; lfo.frequency.value = 1.05;
        var depth = ac.createGain(); depth.gain.value = 0.5;
        lfo.connect(depth); depth.connect(gate.gain); lfo.start();
        var mk = function (hz, g) {
          var o = ac.createOscillator(); o.type = 'sine'; o.frequency.value = hz;
          var g2 = ac.createGain(); g2.gain.value = g;
          o.connect(g2); g2.connect(gate); o.start();
          ring.nodes.push(o, g2);
        };
        mk(466, 1); mk(587, 0.5);
        ring.ac = ac; ring.nodes.push(lfo, depth, gate, master);
      } catch (e) {}
      try { if (navigator.vibrate) navigator.vibrate([500, 300, 500, 300, 500]); } catch (e) {}
      try {
        if (!ring.ticker) ring.ticker = setInterval(function () {
          document.title = (document.title.indexOf('Incoming') === 0 ? '' : 'Incoming call - ') + ring.title;
        }, 900);
      } catch (e) {}
    }

    function stopRingAudio() {
      try { if (navigator.vibrate) navigator.vibrate(0); } catch (e) {}
      if (ring.ticker) { clearInterval(ring.ticker); ring.ticker = null; }
      try { document.title = ring.title; } catch (e) {}
      var ac = ring.ac;
      ring.nodes.forEach(function (n) { try { n.stop && n.stop(); } catch (e) {} try { n.disconnect(); } catch (e) {} });
      ring.nodes = []; ring.ac = null;
      try { if (ac && ac.close) ac.close(); } catch (e) {}
    }

    function stopRing() { stopRingAudio(); }

    /* Notification permission only opens from a gesture, so take it on the first tap of the
       session rather than at page load, where it is silently refused. */
    (function armNotifications() {
      if (!('Notification' in window) || Notification.permission !== 'default') return;
      var once = function () {
        try { Notification.requestPermission(); } catch (e) {}
        document.removeEventListener('pointerdown', once);
      };
      document.addEventListener('pointerdown', once);
    })();

    function browserNotify(title, body, tag) {
      try {
        if (!('Notification' in window) || Notification.permission !== 'granted') return;
        // A hidden tab is where this matters, and where a toast is invisible.
        if (!document.hidden && tag === 'pulse-call') return;
        var n = new Notification(title, { body: body, tag: tag || 'pulse', requireInteraction: false });
        n.onclick = function () { try { window.focus(); } catch (e) {} try { n.close(); } catch (e) {} };
      } catch (e) {}
    }

    /* One heads-up per fetch batch, coalesced by tag, so a flood of messages is not a flood
       of notifications. */
    function messageAlert(m, threadId) {
      try {
        var who = String(m.sender || 'Someone');
        var what = m.fileName ? ('sent a file: ' + m.fileName)
          : String(m.text || 'sent a message').substring(0, 90);
        var where = (threadMeta[threadId] && threadMeta[threadId].title) || 'Everyone';
        browserNotify(who + ' in ' + where, what, 'pulse-msg-' + (threadId || ''));
      } catch (e) {}
    }

    /* ---- server push ----
       The 2 s poll is the fallback; this is the fast path. EventSource is not a timer, so a
       background tab still gets the frame the moment the phone dials.

       EventSource cannot send a header, and the session token that used to ride in this URL was a
       twelve-hour bearer credential sitting in history. The tab trades it for a ticket that lives
       minutes and names only this stream. Because that also expires under a long-lived connection,
       an error closes the connection and re-mints rather than leaving EventSource retrying a dead
       URL; the poll covers the gap in between. */
    var eventsStopped = false;
    var eventsStream = null;
    var eventRetryTimer = null;
    /* Reconnect spacing, in one place rather than in the closure that opened the dead connection.
       A host that just restarted, a tab that woke under an expired Wi-Fi lease and a ticket that
       lapsed mid-stream all land here: a fixed 8 s is 8 s of nothing when the phone is already
       answering, and thirty tabs that retry on the same beat arrive as one stampede. */
    var eventBackoffMs = 1000;
    function eventTicket() {
      // The same cached ticket every other ticketed URL uses; minting a second one per connect
      // spent a POST /api/ticket on every reconnect for a token the cache already held.
      return ticketFor('events');
    }
    function connectEvents() {
      if (!window.EventSource || eventsStopped) return;
      if (eventRetryTimer) { clearTimeout(eventRetryTimer); eventRetryTimer = null; }
      try { if (eventsStream) eventsStream.close(); } catch (e) {}
      eventsStream = null;
      var start = function (url) {
        if (eventsStopped) return;
        var es;
        try { es = new EventSource(url); } catch (e) { return; }
        eventsStream = es;
        var on = function (name, fn) { try { es.addEventListener(name, fn); } catch (e) {} };
        var openedAt = 0;
        // Recorded, not trusted as a success: the phone caps how many streams it will hold, so a
        // connection can open and be cut a moment later. Only one that stayed up earns a reset.
        es.onopen = function () { openedAt = Date.now(); };
        on('call', function () { pollCall(); });
        on('callstate', function () { pollCall(); });
        on('message', function () { try { fetchMessages(); } catch (e) {} });
        on('deleted', function () {
          // The store keeps a tombstone rather than dropping the row, so re-read rather than hide.
          chatRev++;
          try { fetchMessages(); } catch (e) {}
        });
        on('file', function (ev) {
          var d = {}; try { d = JSON.parse(ev.data || '{}'); } catch (e) {}
          try { fetchFiles(); } catch (e) {}
          if (d.name) browserNotify('File received', d.name + ' from ' + (d.from || 'a device'), 'pulse-file');
        });
        on('pair', function () { try { fetchThreads(); fetchInfo(); } catch (e) {} });
        es.onerror = function () {
          // Covers both a lost connection and a ticket that has expired under it. One timer at a
          // time, or EventSource's repeated error events would multiply the reconnects.
          try { es.close(); } catch (e) {}
          if (eventRetryTimer) return;
          if (openedAt && Date.now() - openedAt > 20000) eventBackoffMs = 1000;
          var wait = eventBackoffMs + Math.floor(Math.random() * 250);
          eventBackoffMs = Math.min(eventBackoffMs * 2, 20000);
          eventRetryTimer = setTimeout(function () { eventRetryTimer = null; connectEvents(); }, wait);
        };
      };
      if (!authToken) { start('/api/events'); return; }
      eventTicket().then(function (t) {
        start(t ? '/api/events?ticket=' + encodeURIComponent(t) : '/api/events');
      });
    }
    connectEvents();
    // The tab is going away; stop the timer too, or a pending reconnect outlives the page.
    window.addEventListener('beforeunload', function () {
      eventsStopped = true;
      if (eventRetryTimer) { clearTimeout(eventRetryTimer); eventRetryTimer = null; }
      try { if (eventsStream) eventsStream.close(); } catch (e) {}
    });

    function peerName() {
      var t = el('topbarDevice').textContent.trim();
      return t || 'the other device';
    }

    function fmtCallClock(sec) {
      var m = Math.floor(sec / 60), s = sec % 60;
      return m + ':' + (s < 10 ? '0' : '') + s;
    }

    function persistCall() {
      try {
        if (call.cid && call.state) {
          sessionStorage.setItem('pulseCall', JSON.stringify({
            cid: call.cid, video: call.video, dir: call.dir, state: call.state, startedAt: call.startedAt
          }));
        } else sessionStorage.removeItem('pulseCall');
      } catch (e) {}
    }

    function callHint(txt) {
      var h = el('callHint');
      if (!txt) { h.classList.remove('show'); return; }
      h.textContent = txt;
      h.classList.add('show');
    }

    function paintCall() {
      var incoming = call.dir === 'in';
      var ringing = call.state === 'ringing';
      var on = call.state === 'accepted';
      el('callScreen').classList.add('show');
      el('callKind').textContent = call.video ? 'Video call' : 'Voice call';
      el('callTitle').textContent = ringing && !incoming ? 'Ringing ' + peerName() : peerName();
      el('callStatus').textContent = ringing
        ? (incoming ? 'Incoming ' + (call.video ? 'video' : 'voice') + ' call' : 'Waiting for ' + peerName() + ' to answer')
        : (on ? 'Connected' : 'Ending');
      el('callTimer').style.display = on ? 'block' : 'none';
      el('callPeer').textContent = peerName();
      el('cellAccept').style.display = (ringing && incoming) ? 'flex' : 'none';
      el('cellDecline').style.display = ringing ? 'flex' : 'none';
      el('callDeclineLab').textContent = incoming ? 'Decline' : 'Cancel';
      // While it is still ringing, Decline/Cancel already hangs up; a second red button beside it
      // reads as two different things to press.
      el('cellEnd').style.display = ringing ? 'none' : 'flex';
      el('cellMute').style.display = on ? 'flex' : 'none';
      el('cellCam').style.display = on ? 'flex' : 'none';
      el('cellShare').style.display = on ? 'flex' : 'none';
      el('callMuteLab').textContent = pdMuted ? 'Unmute' : 'Mute';
      el('callYou').textContent = 'You - ' + MY_NAME + (myIp ? ' (' + myIp + ')' : '');
      el('callAvatar').style.background = avColor(incoming ? peerName() : MY_NAME);
      el('callAvatar').textContent = initialsOf(incoming ? peerName() : MY_NAME);
      syncSelfView();
      // The poster only steps aside once an actual frame has landed.
      el('callRemote').style.display = (on && live.gotFrame) ? 'block' : 'none';
      layoutSlots();
      persistCall();
    }

    var flipped = false;

    /* ---- the two feed slots: tap swaps stage/tile, drag parks the tile ---- */

    function applyDrag(s) {
      s.style.transform = s.classList.contains('small')
        ? 'translate(' + (s._dx || 0) + 'px,' + (s._dy || 0) + 'px)' : '';
    }

    function layoutSlots() {
      var self = el('callPip'), remote = el('slotRemote');
      var selfVisible = self.style.display !== 'none';
      // A voice call with no remote video hands the stage to whatever this tab is sending.
      var selfPrimary = flipped;
      if (!selfVisible) selfPrimary = false;
      else if (!call.video && !flipped && (live.sharing || live.camOn)) selfPrimary = true;
      self.classList.toggle('big', selfPrimary);
      self.classList.toggle('small', !selfPrimary);
      remote.classList.toggle('big', !selfPrimary);
      remote.classList.toggle('small', selfPrimary);
      applyDrag(self); applyDrag(remote);
      // An empty tile is just dark furniture - only show the remote slot when it has a feed.
      remote.style.display = (selfPrimary && !live.gotFrame) ? 'none' : 'block';
      el('callPoster').classList.toggle('hide',
        selfPrimary || el('callRemote').style.display === 'block');
    }

    function flipSlots() {
      // The swap promotes this tab's own feed onto the stage. With the camera off and no screen
      // shared there is no feed, and the call would look like a black screen with no way back.
      if (!flipped && !live.camOn && !live.sharing) {
        callHint('Your camera is off - turn it on to put yourself in the big view');
        return;
      }
      flipped = !flipped;
      layoutSlots();
    }

    function wireSlot(node) {
      var st = null;
      node.addEventListener('pointerdown', function (e) {
        if (!node.classList.contains('small')) return;
        // The camera-switch button lives inside the tile: without this its tap would also
        // register as a tile tap and swap the feeds out from under the press.
        if (e.target.closest('.pip-flip')) return;
        e.preventDefault();
        try { node.setPointerCapture(e.pointerId); } catch (err) {}
        st = { x: e.clientX, y: e.clientY, ox: node._dx || 0, oy: node._dy || 0, moved: false };
      });
      node.addEventListener('pointermove', function (e) {
        if (!st) return;
        var dx = e.clientX - st.x, dy = e.clientY - st.y;
        if (!st.moved && Math.abs(dx) + Math.abs(dy) > 8) {
          st.moved = true; node.classList.add('dragging');
        }
        if (st.moved) {
          node._dx = st.ox + dx; node._dy = st.oy + dy;
          var r = el('callStage').getBoundingClientRect();
          node._dx = Math.min(8, Math.max(node._dx, 24 - r.width + node.offsetWidth));
          node._dy = Math.min(8, Math.max(node._dy, 24 - r.height + node.offsetHeight));
          applyDrag(node);
        }
      });
      node.addEventListener('pointerup', function () {
        if (!st) return;
        node.classList.remove('dragging');
        if (!st.moved) flipSlots();
        st = null;
      });
      node.addEventListener('pointercancel', function () {
        st = null; node.classList.remove('dragging');
      });
    }

    /* Which lens the browser has, and so whether the self tile should read as a mirror. Declared
       above the view code because both decide how the tile is painted. */
    var camFacing = 'user';

    /* One place decides what the self tile looks like wherever it currently sits. */
    function syncSelfView() {
      var v = el('callSelf');
      // Only the front lens is a mirror. Flipping to the rear and still seeing the room reversed is
      // how the self tile reads as broken, so the flip decides the transform, not just the stream.
      v.classList.toggle('self-cam', !live.sharing && camFacing === 'user');
      el('callPip').classList.toggle('live', live.camOn || live.sharing);
      el('callCam').classList.toggle('active', live.camOn);
      el('callCam').classList.toggle('slated', !live.camOn);
      el('callCamLab').textContent = live.camOn ? 'Camera' : 'Camera off';
      // The lens swap belongs to a live camera feed, not a shared screen.
      el('callFlip').classList.toggle('show', live.camOn && !live.sharing);
      var on = call.state === 'accepted';
      el('callPip').style.display = (on && (call.video || live.camOn || live.sharing)) ? 'block' : 'none';
      layoutSlots();
    }

    function showCall(cid, video, dir, state, startedAt) {
      call.cid = cid; call.video = !!video; call.dir = dir; call.state = state;
      call.startedAt = state === 'accepted' ? (startedAt || Date.now()) : 0;
      if (dir === 'in' && state === 'ringing') {
        startRing();
        /* Answering from an http tab is a one-way call: the phone is seen and heard, the tab
           is not, and the browser refuses the mic without saying why. Say so before the tap. */
        if (!window.isSecureContext) {
          var sb = el('secureBtn');
          if (sb && tlsUpInfo) sb.style.display = 'inline-flex';
          callHint('This tab is on http, so answering lets them see and hear the phone but the ' +
                   'browser will not release your mic or camera. ' + secureAdvice());
        }
        browserNotify((video ? 'Video call' : 'Call') + ' from ' + peerName(),
          'Open this tab to answer', 'pulse-call');
      } else stopRing();
      paintCall();
      if (call.tick) clearInterval(call.tick);
      call.tick = setInterval(function () {
        if (!call.startedAt) return;
        el('callTimer').textContent = fmtCallClock(Math.floor((Date.now() - call.startedAt) / 1000));
      }, 1000);
      if (!call.watch) call.watch = setInterval(pollCall, 1500);
    }

    async function startCall(video) {
      var res;
      /* Dialing from inside a group thread invites the whole group once the phone answers. */
      var inGroup = currentThread && currentThread.indexOf('group:') === 0;
      try {
        res = await fetch('/api/call/web', {
          method: 'POST',
          headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, authHeaders()),
          body: JSON.stringify({ action: 'ring', name: MY_NAME, video: !!video, group: inGroup ? currentThread : '' })
        });
      } catch (e) { toast('Phone is unreachable'); return; }
      if (!res.ok) { toast('Could not start the call'); return; }
      var j = await res.json().catch(function () { return {}; });
      if (!j.success) { toast(j.error || 'A call is already up'); return; }
      if (inGroup) toast('Group call — the phones join when the owner answers');
      showCall(j.cid, video, 'out', 'ringing', 0);
      // Ask for the devices under this tap. Waiting for the answer means the prompt opens off a
      // timer, which is the case a browser is most likely to answer with a suspended context.
      startCallMedia(true);
    }

    async function pollCall() {
      if (!authToken && REQUIRES_PIN) return;
      try {
        var res = await fetch('/api/call/web', { headers: authHeaders() });
        if (!res.ok) return;
        var j = await res.json();
        var st = j.state || 'ringing';
        if (!j.active) {
          pendingRestore = null;
          if (call.cid) { el('callStatus').textContent = 'Call ended by the other side'; closeCall(true); }
          return;
        }
        if (!call.cid) {
          // Reloaded mid-call: the record on the phone outlived the tab, so rebuild this
          // exact screen and put the media leg back up.
          if (pendingRestore && j.cid === pendingRestore.cid) {
            showCall(j.cid, j.video, pendingRestore.dir, st, pendingRestore.startedAt);
            pendingRestore = null;
            if (st === 'accepted') startCallMedia();
            return;
          }
          pendingRestore = null;
          if (j.incoming) showCall(j.cid, j.video, 'in', st, 0);
          return;
        }
        if (j.cid && j.cid !== call.cid) return;
        if (st === call.state) return;
        if (st === 'declined') { el('callStatus').textContent = 'Call declined'; closeCall(true); return; }
        if (st === 'ended') { el('callStatus').textContent = 'Call ended by the other side'; closeCall(true); return; }
        call.state = st;
        if (st === 'accepted' && !call.startedAt) call.startedAt = Date.now();
        paintCall();
        if (st === 'accepted') startCallMedia();
      } catch (e) {}
    }

    function callAction(action) {
      fetch('/api/call/web', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, authHeaders()),
        body: JSON.stringify({ action: action, cid: call.cid })
      }).catch(function () {});
    }

    function acceptCall() {
      call.state = 'accepted'; call.startedAt = Date.now(); paintCall();
      callAction('accept');
      // A tap is the only moment a browser reliably treats as user activation, which is what lets
      // the microphone prompt open and the audio context start un-suspended.
      startCallMedia(true);
    }

    function declineCall() { callAction('decline'); closeCall(false); }

    function endCall() { callAction('end'); closeCall(false); }

    /* ---- uplink: capture, encode, batch ---- */

    function pushUp(type, bytes) {
      var n = bytes.length;
      var f = new Uint8Array(5 + n);
      f[0] = type;
      f[1] = (n >>> 24) & 255; f[2] = (n >>> 16) & 255; f[3] = (n >>> 8) & 255; f[4] = n & 255;
      f.set(bytes, 5);
      live.up.push(f);
      // Three batches in flight is already a third of a second behind; shed the oldest.
      if (live.sending >= 3) live.up.splice(0, Math.max(1, live.up.length - 24));
    }

    function b64(u8) {
      var s = '', CH = 32768;
      for (var i = 0; i < u8.length; i += CH) {
        s += String.fromCharCode.apply(null, u8.subarray(i, Math.min(i + CH, u8.length)));
      }
      return btoa(s);
    }

    function flushUp() {
      if (!call.cid) return;
      // The phone drops a batch for a call it has not opened yet, so there is no point queueing
      // anything while it is still ringing: the buffer would only grow and then arrive late.
      if (call.state !== 'accepted') { live.up = []; return; }
      if (!live.up.length) return;
      var parts = live.up; live.up = [];
      var total = 0;
      parts.forEach(function (p) { total += p.length; });
      var all = new Uint8Array(total), o = 0;
      parts.forEach(function (p) { all.set(p, o); o += p.length; });
      live.sending++;
      fetch('/api/webcall/up', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, authHeaders()),
        body: JSON.stringify({ cid: call.cid, frames: b64(all) })
      }).then(function (res) {
        live.sending--;
        // A rejected batch used to vanish: the tab looked connected, the meter moved, and the other
        // side heard nothing. One failure is the usual race with the answer landing, so only a run
        // of them is worth saying out loud.
        if (!res.ok) {
          if (++live.upErr === 8) {
            callHint('The phone is refusing this tab’s voice - it does not have the call open.');
          }
        } else if (live.upErr) {
          live.upErr = 0;
        }
      }, function () { live.sending--; });
    }

    function attachMic(stream) {
      var AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return;
      try { live.cap = new AC({ sampleRate: 16000 }); } catch (e) { live.cap = new AC(); }
      live.inRate = live.cap.sampleRate;
      live.micStream = stream;
      live.srcNode = live.cap.createMediaStreamSource(stream);
      // The microphone sits behind a gain so muting it does not also mute a shared screen; the
      // share connects to the same node, and the graph sums them before anything is encoded.
      live.micGain = live.cap.createGain();
      live.micGain.gain.value = pdMuted ? 0 : 1;
      live.srcNode.connect(live.micGain);
      // ScriptProcessor rather than an AudioWorklet: the module would have to be a separate
      // URL or a blob, and 16 kHz mono passthrough does not need a real-time thread.
      live.sp = live.cap.createScriptProcessor(1024, 1, 1);
      live.sp.onaudioprocess = function (ev) {
        var out = ev.outputBuffer.getChannelData(0);
        for (var i = 0; i < out.length; i++) out[i] = 0;
        var d = ev.inputBuffer.getChannelData(0);
        // Muted with nothing to share is 16 kHz of silence the peer already knows about.
        if (pdMuted && !live.shareSrc) return;
        var target = live.inRate === 16000 ? d.length : Math.round(d.length * 16000 / live.inRate);
        var i16 = new Int16Array(target);
        for (var j = 0; j < target; j++) {
          var t = live.inRate === 16000 ? j : j * d.length / target;
          var k = Math.min(d.length - 1, Math.floor(t));
          // A voice plus a video can add up past full scale, and an Int16Array wraps that into
          // crackle rather than clipping it, so the sum is brought back inside first.
          var v = d[k];
          if (v > 1) v = 1; else if (v < -1) v = -1;
          i16[j] = v < 0 ? v * 32768 : v * 32767;
        }
        pushUp(T_AUDIO, new Uint8Array(i16.buffer));
      };
      live.micGain.connect(live.sp);
      live.sp.connect(live.cap.destination);
      if (live.cap.state === 'suspended') live.cap.resume();
      // An AudioContext raised off a timer rather than a tap can stay suspended, and a suspended one
      // never fires onaudioprocess - the tab looks connected and sends nothing. The downlink already
      // re-resumes on every chunk it plays; the uplink needs the same patience.
      if (!live.resumeTick) {
        live.resumeTick = setInterval(function () {
          try { if (live.cap && live.cap.state === 'suspended') live.cap.resume(); } catch (e) {}
        }, 1000);
      }
    }

    function startFrameLoop() {
      var iv = live.sharing ? 130 : 120;
      if (live.vfTimer && live.vfIv === iv) return;
      if (live.vfTimer) clearInterval(live.vfTimer);
      live.vfIv = iv;
      live.vfTimer = setInterval(grabFrame, iv);
    }

    function grabFrame() {
      if (live.busy) return;
      var v = el('callSelf');
      if (!v.videoWidth) return;
      if (!live.camOn && !live.sharing) return;
      var share = live.sharing;
      var w = share ? 1600 : 1100;
      if (v.videoWidth < w) w = v.videoWidth;
      var h = Math.max(2, Math.round(v.videoHeight * (w / v.videoWidth)));
      var c = live.canvas || (live.canvas = document.createElement('canvas'));
      if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
      c.getContext('2d').drawImage(v, 0, 0, w, h);
      live.busy = true;
      c.toBlob(function (b) {
        live.busy = false;
        if (!b || !live.upTimer) return;
        b.arrayBuffer().then(function (ab) {
          pushUp(live.sharing ? T_SCREEN : T_CAMERA, new Uint8Array(ab));
        });
      }, 'image/jpeg', share ? 0.75 : 0.72);
    }

    function sendCallState() {
      pushUp(T_STATE, new TextEncoder().encode(JSON.stringify({ sharing: live.sharing, muted: pdMuted })));
    }

    /* ---- downlink: one chunked body, decoded frame by frame ---- */

    async function pumpDown(signal) {
      var buf = new Uint8Array(0);
      try {
        var res = await fetch('/api/webcall/down?cid=' + encodeURIComponent(call.cid),
          { headers: authHeaders(), signal: signal });
        if (!res.ok || !res.body) return;
        var rd = res.body.getReader();
        while (true) {
          var r = await rd.read();
          if (r.done) break;
          var chunk = r.value;
          if (!chunk || !chunk.length) continue;
          var merged = new Uint8Array(buf.length + chunk.length);
          merged.set(buf); merged.set(chunk, buf.length); buf = merged;
          var i = 0;
          while (i + 5 <= buf.length) {
            var type = buf[i];
            var len = buf[i + 1] * 16777216 + (buf[i + 2] << 16) + (buf[i + 3] << 8) + buf[i + 4];
            if (len <= 0 || len > 4194304) { i = buf.length; break; }
            if (i + 5 + len > buf.length) break;
            handleDown(type, buf.subarray(i + 5, i + 5 + len));
            i += 5 + len;
          }
          if (i) buf = buf.slice(i);
        }
      } catch (e) { /* aborted, or the leg is gone */ }
    }

    function handleDown(type, bytes) {
      if (type === T_AUDIO) return playPcm(bytes);
      if (type === T_CAMERA || type === T_SCREEN) return showFeed(bytes, type === T_SCREEN);
      if (type === T_STATE) {
        var j = null;
        try { j = JSON.parse(new TextDecoder().decode(bytes)); } catch (e) { return; }
        el('callShareTag').classList.toggle('show', !!(j && j.sharing));
        el('callShareTxt').textContent = peerName() + ' is sharing their screen';
        // Without this the tab keeps saying "Connected" while the other side says nothing.
        peerMuted = !!(j && j.muted);
        el('callPeer').textContent = peerName() + (peerMuted ? ' - muted' : '');
        return;
      }
    }

    function showFeed(bytes, isScreen) {
      if (live.lastUrl) URL.revokeObjectURL(live.lastUrl);
      live.lastUrl = URL.createObjectURL(new Blob([bytes], { type: 'image/jpeg' }));
      var img = el('callRemote');
      img.src = live.lastUrl;
      img.classList.toggle('cover', !isScreen);
      if (!live.gotFrame) { live.gotFrame = true; paintCall(); }
    }

    function playPcm(bytes) {
      var AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return;
      if (!live.ac) { try { live.ac = new AC(); } catch (e) { return; } live.next = 0; }
      var ac = live.ac;
      if (ac.state === 'suspended') ac.resume();
      var n = bytes.length >> 1;
      if (!n) return;
      var ab = ac.createBuffer(1, n, 16000);
      var ch = ab.getChannelData(0);
      var dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      for (var i = 0; i < n; i++) ch[i] = dv.getInt16(i * 2, true) / 32768;
      // Pace the buffers back-to-back, and shed latency if the tab was throttled.
      if (live.next < ac.currentTime + 0.01 || live.next - ac.currentTime > 0.5) {
        live.next = ac.currentTime + 0.06;
      }
      var src = ac.createBufferSource();
      src.buffer = ab;
      src.connect(ac.destination);
      src.start(live.next);
      live.next += ab.duration;
    }

    /* ---- bring the leg up and down ---- */

    async function startCallMedia(fromGesture) {
      // The transport is set up once and never torn down by a failed capture: it is the downlink
      // that keeps a tab hearing the call, and it has to survive the mic being refused.
      if (!live.upTimer) {
        live.gotFrame = false;
        callHint('');
        live.upTimer = setInterval(flushUp, 100);
      }
      // The downlink is asked for only once the phone has the call open. A pump started while the
      // phone is still ringing is refused and returns, and a refused pump does not restart itself.
      if (call.state === 'accepted' && !live.downAbort) {
        live.downAbort = new AbortController();
        pumpDown(live.downAbort.signal);
      }
      // Capture is the half that can fail, so capture is the half that gets retried. Guarding this
      // on `upTimer` instead meant one declined prompt left the tab deaf for the rest of the call
      // with no way back short of a reload.
      if (live.micStream || live.capturing) return;
      if (live.upFail && live.upFail !== 'insecure' && !fromGesture) return;
      var md = navigator.mediaDevices;
      if (!md || !md.getUserMedia) {
        needsSecure = true;
        live.upFail = 'insecure';
        callHint('Browsers only hand over the mic and camera on a secure origin. ' + secureAdvice());
        fetchInfo();
        return;
      }
      live.capturing = true;
      try {
        var stream = await md.getUserMedia({
          audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
          video: call.video ? camConstraints() : false
        });
        live.upFail = '';
        attachMic(stream);
        if (call.video) {
          live.camStream = stream;
          live.camOn = true;
          el('callSelf').srcObject = stream;
          startFrameLoop();
          syncSelfView();
        }
      } catch (e) {
        var name = (e && e.name) || '';
        if (!window.isSecureContext) {
          needsSecure = true;
          live.upFail = 'insecure';
          callHint('This page is on http, so the browser will not release the mic or camera. ' +
                   secureAdvice());
          fetchInfo();
        } else if (name === 'NotFoundError' || name === 'OverconstrainedError') {
          live.upFail = 'missing';
          callHint('No microphone the browser could use - the call still receives voice and video.');
        } else {
          live.upFail = 'denied';
          callHint('Microphone or camera was blocked - the call still receives voice and video.');
        }
      } finally {
        live.capturing = false;
      }
    }

    function toggleCallMute() {
      pdMuted = !pdMuted;
      // Stopping the microphone track, not just zeroing samples: a muted tab that keeps
      // encoding silence still spends the bandwidth, and track.enabled is what the browser
      // shows as "mic off" in the tab hint and on the OS indicator.
      if (live.cap) {
        try {
          (live.micStream ? live.micStream.getAudioTracks() : []).forEach(function (t) {
            t.enabled = !pdMuted;
          });
        } catch (e) {}
      }
      if (live.micGain) live.micGain.gain.value = pdMuted ? 0 : 1;
      el('callMute').classList.toggle('active', pdMuted);
      el('callMute').setAttribute('title', pdMuted ? 'Unmute' : 'Mute');
      el('callMute').setAttribute('aria-label', pdMuted ? 'Unmute microphone' : 'Mute microphone');
      el('callMuteLab').textContent = pdMuted ? 'Unmute' : 'Mute';
      sendCallState();
    }

    function camConstraints() {
      return {
        facingMode: { ideal: camFacing },
        width: { ideal: 1280 },
        height: { ideal: 720 }
      };
    }

    // A phone answers facingMode; a laptop answers deviceId and usually nothing else, so asking the
    // browser for "environment" is why the flip looked dead on a computer with two cameras. Try the
    // real device list first and only fall back to the lens name.
    function flipCallCamera() {
      if (!live.camStream) { callHint('Turn the camera on first'); return; }
      var md = navigator.mediaDevices;
      if (!md || !md.getUserMedia) return;
      var vt = live.camStream.getVideoTracks();
      var was = vt.length && vt[0].getSettings ? vt[0].getSettings() : {};
      var cur = was.deviceId || '';
      var pick = function (list) {
        var cams = (list || []).filter(function (d) { return d.kind === 'videoinput'; });
        if (cams.length < 2) return null;
        var at = -1;
        for (var i = 0; i < cams.length; i++) { if (cams[i].deviceId === cur) at = i; }
        return cams[(at + 1) % cams.length].deviceId;
      };
      var list = md.enumerateDevices
        ? md.enumerateDevices().then(function (d) { return d; }).catch(function () { return []; })
        : Promise.resolve([]);
      list.then(pick).then(function (want) {
        var video = want
          ? { deviceId: { exact: want }, width: { ideal: 1280 }, height: { ideal: 720 } }
          : { facingMode: { exact: camFacing === 'user' ? 'environment' : 'user' }, width: { ideal: 1280 }, height: { ideal: 720 } };
        return md.getUserMedia({ video: video });
      }).then(function (s) {
        live.camStream.getVideoTracks().forEach(function (t) { t.stop(); });
        live.camStream = s;
        el('callSelf').srcObject = s;
        var st = s.getVideoTracks()[0];
        var set = st && st.getSettings ? st.getSettings() : {};
        // The browser knows which lens it opened even when the page never asked it by name.
        if (set.facing === 'user' || set.facing === 'environment') camFacing = set.facing;
        if (!live.camOn) toggleCallCamera();
        syncSelfView();
        callHint('');
      }).catch(function () { callHint('Only one camera on this device'); });
    }

    function toggleCallCamera() {
      if (!live.camStream) {
        var md = navigator.mediaDevices;
        if (!md || !md.getUserMedia || !window.isSecureContext) {
          /* The same way the microphone and the screen get across: say why, and only offer the jump
             when the phone's https port is actually listening. Pointing at a "Secure mode" button
             that is not there — or one that leads to a port nobody answers — leaves the user with a
             dead end and no reason. */
          needsSecure = true;
          callHint('The camera is only offered on a secure origin. ' + secureAdvice());
          if (tlsUpInfo) el('secureBtn').style.display = 'inline-flex';
          fetchInfo();
          return;
        }
        md.getUserMedia({ video: camConstraints() })
          .then(function (s) {
            live.camStream = s;
            live.camOn = true;
            el('callSelf').srcObject = s;
            startFrameLoop();
            syncSelfView();
            callHint('');
          })
          .catch(function () { callHint('Camera blocked by the browser'); });
        return;
      }
      live.camOn = !live.camOn;
      live.camStream.getVideoTracks().forEach(function (t) { t.enabled = live.camOn; });
      syncSelfView();
    }

    function toggleCallShare() {
      if (live.sharing) { stopCallShare(); return; }
      var md = navigator.mediaDevices;
      if (!md || !md.getDisplayMedia) {
        // The picker is hidden outright on an insecure origin, so this is the usual reason a
        // visitor "cannot share" - point them at the fix instead of at a dead end.
        needsSecure = true;
        callHint('Screen sharing is only offered on a secure origin. ' + secureAdvice());
        if (tlsUpInfo) el('secureBtn').style.display = 'inline-flex';
        fetchInfo();
        return;
      }
      var shareReq = { video: { width: { ideal: 1920 }, height: { ideal: 1080 }, frameRate: { ideal: 15 } }, audio: true };
      md.getDisplayMedia(shareReq).then(function (s) { openShare(s); }, function () {
        // Browsers that will not hand over system audio still hand over the picture, so losing the
        // sound here must not lose the share.
        md.getDisplayMedia({ video: shareReq.video, audio: false }).then(openShare, function () {
          callHint('Screen sharing was cancelled');
        });
      });
    }

    function openShare(s) {
      if (s.getAudioTracks().length && live.cap && live.sp) {
        // Summed into the microphone's node by the graph; see attachMic.
        live.shareSrc = live.cap.createMediaStreamSource(s);
        live.shareSrc.connect(live.sp);
      }
      live.shareStream = s;
      live.sharing = true;
      el('callSelf').srcObject = s;
      el('callShareBtn').classList.add('active');
      el('callShareLab').textContent = 'Stop';
      var tr = s.getVideoTracks()[0];
      if (tr) tr.addEventListener('ended', stopCallShare);
      syncSelfView();
      startFrameLoop();
      sendCallState();
      callHint('');
    }

    function stopCallShare() {
      if (live.shareSrc) {
        try { live.shareSrc.disconnect(); } catch (e) {}
        live.shareSrc = null;
      }
      if (live.shareStream) {
        live.shareStream.getTracks().forEach(function (t) { t.stop(); });
        live.shareStream = null;
      }
      live.sharing = false;
      el('callShareBtn').classList.remove('active');
      el('callShareLab').textContent = 'Share';
      el('callSelf').srcObject = live.camOn && live.camStream ? live.camStream : null;
      syncSelfView();
      startFrameLoop();
      sendCallState();
    }

    function stopCallMedia() {
      if (live.upTimer) { clearInterval(live.upTimer); live.upTimer = null; }
      if (live.vfTimer) { clearInterval(live.vfTimer); live.vfTimer = null; }
      if (live.downAbort) { live.downAbort.abort(); live.downAbort = null; }
      [live.micStream, live.camStream, live.shareStream].forEach(function (s) {
        if (s && s !== live.micStream) s.getTracks().forEach(function (t) { t.stop(); });
      });
      if (live.micStream) live.micStream.getTracks().forEach(function (t) { t.stop(); });
      if (live.sp) { try { live.sp.disconnect(); } catch (e) {} live.sp.onaudioprocess = null; live.sp = null; }
      if (live.srcNode) { try { live.srcNode.disconnect(); } catch (e) {} live.srcNode = null; }
      if (live.micGain) { try { live.micGain.disconnect(); } catch (e) {} live.micGain = null; }
      if (live.shareSrc) { try { live.shareSrc.disconnect(); } catch (e) {} live.shareSrc = null; }
      if (live.cap) { try { live.cap.close(); } catch (e) {} live.cap = null; }
      if (live.ac) { try { live.ac.close(); } catch (e) {} live.ac = null; }
      if (live.resumeTick) { clearInterval(live.resumeTick); live.resumeTick = null; }
      live.micStream = live.camStream = live.shareStream = null;
      live.up = []; live.sending = 0; live.gotFrame = false;
      live.capturing = false; live.upFail = ''; live.upErr = 0;
      live.sharing = false; live.camOn = false; live.next = 0;
      camFacing = 'user';
      pdMuted = false;
      el('callSelf').srcObject = null;
      el('callRemote').style.display = 'none';
      if (live.lastUrl) { URL.revokeObjectURL(live.lastUrl); live.lastUrl = ''; }
      el('callMute').classList.remove('active');
      el('callMuteLab').textContent = 'Mute';
      el('callCam').classList.remove('active');
      el('callShareBtn').classList.remove('active');
      el('callShareLab').textContent = 'Share';
      // Put the slots back the way they shipped: remote on the stage, self in the corner.
      flipped = false;
      el('callPip')._dx = el('callPip')._dy = 0;
      el('slotRemote')._dx = el('slotRemote')._dy = 0;
      el('callPip').className = 'feed-slot small call-pip';
      el('slotRemote').className = 'feed-slot big';
      el('callPip').style.transform = '';
      el('slotRemote').style.transform = '';
      callHint('');
    }

    function closeCall(keepMessage) {
      var last = el('callStatus').textContent;
      var secs = call.startedAt ? Math.floor((Date.now() - call.startedAt) / 1000) : 0;
      if (call.tick) { clearInterval(call.tick); call.tick = null; }
      if (call.watch) { clearInterval(call.watch); call.watch = null; }
      stopRing();
      stopCallMedia();
      call.cid = ''; call.state = ''; call.startedAt = 0;
      el('callScreen').classList.remove('show');
      persistCall();
      // "Call ended" with no length reads like the call never happened.
      var line = secs ? ('Call ended - ' + fmtCallClock(secs)) : (last || 'Call ended');
      if (keepMessage || secs) toast(line);
    }

    // Watch for the phone dialling this tab even while no call is open here.
    setInterval(function () { if (!call.cid) pollCall(); }, 2000);
    // Background tabs throttle timers hard; poll the instant the user comes back, so a call
    // started while the tab was buried shows up as soon as they look at it.
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden && !call.cid) pollCall();
    });
    wireSlot(el('slotRemote'));
    wireSlot(el('callPip'));
    // The lens-swap button sits inside a slot that answers to tap and drag; its presses
    // must never reach the slot handlers.
    el('callFlip').addEventListener('pointerdown', function (e) { e.stopPropagation(); });


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
    // The head script already chose the palette; this only syncs the button's word to the intent
    // ("Auto" and a resolved dark look the same on screen but mean different things to keep).
    // Some browsers have no colour-emoji font, so the tray would post empty boxes.
    if (!browserHasColorEmoji()) el('emojiBtn').style.display = 'none';
    setView(viewFromHash());
    setLayout('grid');
    setHistoryLayout('grid');
    renderEmpty(el('filesGrid'), 'Loading files…');
    el('selfNameInput').value = MY_NAME;
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
    // Plain http cannot send a mic or camera out of a browser. The phone normally bounces
    // http visits to the https port automatically; this only shows if that listener is still
    // coming up, so keep asking until it is and offer the jump instead of reciting an address.
    if (!window.isSecureContext && location.hostname !== 'localhost' && location.hostname !== '127.0.0.1') {
      setTimeout(function () {
        toast('This page is on http, so calls can ring and receive but not send. ' + secureAdvice());
      }, 1400);
      var secureWatch = setInterval(function () {
        if (secureOffered) { clearInterval(secureWatch); return; }
        fetchInfo();
      }, 6000);
    }
  </script>
</body>
</html>
        """.trimIndent()
    }
}
