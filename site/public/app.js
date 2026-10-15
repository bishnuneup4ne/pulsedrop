/* The page's only data source is its own Function; nothing here is a placeholder number. */
(function () {
  "use strict";

  var ENDPOINT = "/functions/v1/app";
  var REFRESH_MS = 60000;

  var countEl = document.getElementById("count");
  var noteEl = document.getElementById("countNote");
  var retryEl = document.getElementById("retry");
  var versionsEl = document.getElementById("versions");
  var versionListEl = document.getElementById("versionList");

  function pluralDevices(n) {
    return n === 1 ? "device has" : "devices have";
  }

  function longDate(iso) {
    if (typeof iso !== "string") return null;
    var d = new Date(iso);
    if (isNaN(d.getTime())) return null;
    return d.toLocaleDateString(undefined, { day: "numeric", month: "long", year: "numeric" });
  }

  function figureSpan(className, text) {
    var span = document.createElement("span");
    span.className = className;
    span.textContent = text;
    countEl.replaceChildren(span);
  }

  function showPending() {
    figureSpan("pending", "Counting…");
    noteEl.textContent = "";
    retryEl.hidden = true;
  }

  function showProblem(message) {
    figureSpan("problem", message);
    noteEl.textContent = "The number lives on this site, so a failed read is the site's problem, " +
      "not the app's. It does not mean installs stopped.";
    retryEl.hidden = false;
  }

  function showCount(total, firstSeen) {
    countEl.textContent = total.toLocaleString();
    var since = longDate(firstSeen);
    noteEl.textContent = pluralDevices(total) + " installed PulseDrop" +
      (since ? ", counting since " + since + "." : ".");
    retryEl.hidden = true;
  }

  async function getAction(action) {
    var res = await fetch(ENDPOINT + "?action=" + encodeURIComponent(action), {
      method: "GET",
      credentials: "same-origin",
      headers: { accept: "application/json" },
    });
    if (!res.ok) throw new Error("http_" + res.status);
    var body = await res.json();
    if (!body || typeof body !== "object" || body.ok !== true || body.action !== action) {
      throw new Error("bad_shape");
    }
    return body;
  }

  function renderVersions(body) {
    if (!Array.isArray(body.versions) || body.versions.length === 0) {
      versionsEl.hidden = true;
      return;
    }
    versionListEl.textContent = "";
    body.versions.forEach(function (row) {
      if (!row || typeof row.version !== "string" || !Number.isSafeInteger(row.devices)) return;
      var li = document.createElement("li");
      li.textContent = row.version + " · " + row.devices.toLocaleString();
      versionListEl.appendChild(li);
    });
    versionsEl.hidden = versionListEl.childElementCount === 0;
    var h3 = versionsEl.querySelector("h3");
    if (h3) h3.textContent = body.partial ? "Installed on (most recent)" : "Installed on";
  }

  var inFlight = false;

  async function load() {
    if (inFlight) return;
    inFlight = true;
    try {
      var counted = await getAction("count");
      if (!Number.isSafeInteger(counted.installs) || counted.installs < 0) throw new Error("bad_count");
      showCount(counted.installs, counted.first_seen);
      try {
        renderVersions(await getAction("versions"));
      } catch (e) {
        versionsEl.hidden = true;
      }
    } catch (e) {
      showProblem("Count unavailable");
    } finally {
      inFlight = false;
    }
  }

  showPending();
  load();
  retryEl.addEventListener("click", load);

  var timer = setInterval(function () {
    if (!document.hidden) load();
  }, REFRESH_MS);
  document.addEventListener("visibilitychange", function () {
    if (!document.hidden) load();
  });
  window.addEventListener("pagehide", function () { clearInterval(timer); });

  // ---- theme ----

  var KEY = "pulsedrop-theme";
  var MODES = ["system", "dark", "light"];
  var toggleEl = document.getElementById("themeToggle");
  var labelEl = document.getElementById("themeLabel");

  function storedMode() {
    try {
      var v = localStorage.getItem(KEY);
      return MODES.indexOf(v) >= 0 ? v : "system";
    } catch (e) { return "system"; }
  }

  function applyMode(mode) {
    var dark = mode === "dark" ||
      (mode === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
    document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
    var tc = document.getElementById("themeColor");
    if (tc) tc.setAttribute("content", dark ? "#0B0B0C" : "#FFFFFF");
    labelEl.textContent = mode === "system" ? "System" : mode === "dark" ? "Dark" : "Light";
  }

  var mode = storedMode();
  applyMode(mode);
  toggleEl.addEventListener("click", function () {
    mode = MODES[(MODES.indexOf(mode) + 1) % MODES.length];
    try { localStorage.setItem(KEY, mode); } catch (e) { /* private mode: this click still works */ }
    applyMode(mode);
  });
  if (window.matchMedia) {
    var mq = window.matchMedia("(prefers-color-scheme: dark)");
    var follow = function () { if (mode === "system") applyMode(mode); };
    if (mq.addEventListener) mq.addEventListener("change", follow);
    else if (mq.addListener) mq.addListener(follow);
  }
})();
