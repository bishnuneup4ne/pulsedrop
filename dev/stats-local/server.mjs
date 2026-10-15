// Local-only harness: exercises site/functions/handler.mjs against a fake supabase client so the
// routing, validation and count contract can be checked on a machine with no database and no Deno.
// It stays out of webDirectory and functionDirectory, so it is never part of a published package.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { handleStats } from "../../site/functions/handler.mjs";

const rows = new Map(); // install_id -> { version, reported_at }
const ledger = [];      // every database call the handler attempted, GET/PATCH/POST style

function nowIso(offsetMs = 0) {
  return new Date(Date.now() + offsetMs).toISOString();
}

// A couple of pre-existing installs so the first page is not an empty table.
rows.set("3f2a1c9d-6b4e-4a11-9c2d-8e7f0a1b2c3d", { version: "1.5.0", reported_at: nowIso(-86400000 * 9) });
rows.set("7b9c2d10-4e5f-46a8-b0c1-d2e3f4a5b6c7", { version: "1.6.0", reported_at: nowIso(-86400000 * 3) });

function builder(table, columns, options) {
  const state = { order: null, limit: null, eq: [], filter: null };
  const api = {
    order(column, cfg) { state.order = { column, ascending: cfg?.ascending !== false }; return api; },
    limit(n) { state.limit = n; return api; },
    eq(column, value) { state.eq.push([column, value]); return api; },
    then(resolve, reject) {
      return Promise.resolve().then(() => {
        const all = [...rows.entries()].map(([id, row]) => ({ install_id: id, ...row }));
        for (const [column, value] of state.eq) {
          if (!all.some((r) => r[column] === value)) {
            ledger.push({ read: "GET", table, columns, filtered: true, miss: true });
          }
        }
        // Exact count is the Content-Range total: every matching row, not the returned slice.
        const count = all.length;
        let data = all;
        if (state.order) {
          const key = state.order.column;
          data = [...data].sort((a, b) => {
            const x = a[key], y = b[key];
            const cmp = x === y ? 0 : x < y ? -1 : 1;
            return state.order.ascending ? cmp : -cmp;
          });
        }
        if (state.limit !== null) data = data.slice(0, state.limit);
        ledger.push({ read: "GET", table, columns, count, returned: data.length });
        const trimmed = data.map((row) => {
          const wanted = columns.split(/[,\s]+/);
          const out = {};
          for (const w of wanted) if (w in row) out[w] = row[w];
          return out;
        });
        return { data: trimmed, count, error: null };
      }).then(resolve, reject);
    },
  };
  void options;
  return api;
}

const fakeSupabase = {
  from(table) {
    return {
      select(columns, options) { return builder(table, columns, options); },
      upsert(payload, opts) {
        const id = payload.install_id;
        const ignored = opts?.ignoreDuplicates === true && rows.has(id);
        if (!ignored) rows.set(id, { version: payload.version, reported_at: payload.reported_at });
        ledger.push({
          read: "POST", table, onConflict: opts?.onConflict,
          ignored, id, version: payload.version,
        });
        const outcome = ignored ? { data: [], error: null } : { data: [{ install_id: id }], error: null };
        // The real SDK chains .select() onto an upsert; the handler relies on that shape.
        const settled = Promise.resolve(outcome);
        return { select: () => settled, then: (ok, no) => settled.then(ok, no) };
      },
    };
  },
};

const types = { ".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8", ".svg": "image/svg+xml" };

const pub = join(import.meta.dirname, "..", "..", "site", "public");

const server = createServer(async (req, res) => {
  const url = new URL(req.url, "http://127.0.0.1");
  try {
    if (url.pathname === "/functions/v1/app") {
      const request = new Request(url, {
        method: req.method,
        headers: req.headers,
        body: req.method === "POST" ? await new Response(readable(req)).text() : undefined,
      });
      const response = await handleStats({ request, supabase: fakeSupabase });
      // Forward the handler's own headers, so a 405 really shows its Allow in this preview.
      const out = {};
      response.headers.forEach((value, key) => {
        if (key !== "content-length" && key !== "transfer-encoding") out[key] = value;
      });
      res.writeHead(response.status, out);
      res.end(await response.text());
      return;
    }
    if (req.method === "GET" && url.pathname === "/_ledger") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(ledger));
      return;
    }
    const rel = normalize(url.pathname === "/" ? "index.html" : url.pathname.slice(1));
    if (rel.startsWith("..")) { res.writeHead(400); res.end("bad path"); return; }
    const body = await readFile(join(pub, rel));
    res.writeHead(200, { "content-type": types[extname(rel)] || "application/octet-stream" });
    res.end(body);
  } catch (err) {
    res.writeHead(404, { "content-type": "text/plain" });
    res.end(String(err && err.message));
  }
});

function readable(nodeReq) {
  return new ReadableStream({
    start(controller) {
      nodeReq.on("data", (c) => controller.enqueue(c));
      nodeReq.on("end", () => controller.close());
      nodeReq.on("error", (e) => controller.error(e));
    },
  });
}

const port = Number(process.argv[2] || 8000);
server.listen(port, "127.0.0.1", () => {
  console.log(`local stats preview: http://127.0.0.1:${port}`);
});
