// PulseDrop install stats: one public aggregate read, and one narrow write where a device may
// announce itself exactly once, keyed by an id it generated for that install. A reinstall from
// the same install is dropped rather than re-counted, and the app never reports on an upgrade.
const json = (body, status = 200, headers = {}) => Response.json(body, {
  status,
  headers: { "cache-control": "no-store", ...headers },
});

// A v4 UUID, as the app's InstallReporter generates it per fresh install.
const installIdShape = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
// Release names as GitHub spells them, bounded so a client cannot store prose in a version.
const versionShape = /^[0-9]+\.[0-9]+(?:\.[0-9]+){0,2}$/;
const maxBodyBytes = 512;
const versionRowCap = 5000;

function isCount(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

async function readAnnouncement(request) {
  const type = request.headers.get("content-type") || "";
  if (!type.toLowerCase().startsWith("application/json")) {
    return { error: "invalid_content_type", status: 415 };
  }
  const declared = Number(request.headers.get("content-length"));
  if (Number.isSafeInteger(declared) && declared > maxBodyBytes) {
    return { error: "body_too_large", status: 413 };
  }
  let text;
  try {
    text = await request.text();
  } catch {
    return { error: "unreadable_body", status: 400 };
  }
  if (text.length > maxBodyBytes) return { error: "body_too_large", status: 413 };
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    return { error: "invalid_json", status: 400 };
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return { error: "invalid_json", status: 400 };
  }
  const id = body.install_id;
  if (typeof id !== "string" || !installIdShape.test(id)) {
    return { error: "invalid_install_id", status: 400 };
  }
  const version = body.version;
  if (typeof version !== "string" || !versionShape.test(version)) {
    return { error: "invalid_version", status: 400 };
  }
  return { installId: id.toLowerCase(), version };
}

export async function handleStats({ request, supabase }) {
  // Query parameters avoid depending on the physical Function pathname.
  const action = new URL(request.url).searchParams.get("action");
  try {
    if (action === "count") {
      if (request.method !== "GET") {
        return json({ error: "method_not_allowed" }, 405, { allow: "GET" });
      }
      // The count comes from Content-Range, so limit(1) bounds the rows and not the total.
      const { count, data, error } = await supabase.from("installs")
        .select("reported_at", { count: "exact" })
        .order("reported_at", { ascending: true })
        .limit(1);
      if (error || !isCount(count)) return json({ error: "database_request_failed" }, 503);
      const first = Array.isArray(data) && data.length > 0 ? data[0].reported_at : null;
      return json({ ok: true, action: "count", installs: count, first_seen: typeof first === "string" ? first : null });
    }

    if (action === "versions") {
      if (request.method !== "GET") {
        return json({ error: "method_not_allowed" }, 405, { allow: "GET" });
      }
      const { count, data, error } = await supabase.from("installs")
        .select("version", { count: "exact" })
        .limit(versionRowCap);
      if (error || !isCount(count) || !Array.isArray(data)) {
        return json({ error: "database_request_failed" }, 503);
      }
      const byVersion = new Map();
      for (const row of data) {
        const key = typeof row.version === "string" ? row.version : "unknown";
        byVersion.set(key, (byVersion.get(key) || 0) + 1);
      }
      const versions = [...byVersion.entries()]
        .map(([version, devices]) => ({ version, devices }))
        .sort((a, b) => b.devices - a.devices || (a.version < b.version ? 1 : -1));
      // False once the cap is reached: the breakdown is then a sample of the newest rows.
      return json({ ok: true, action: "versions", installs: count, versions, partial: count > data.length });
    }

    if (action === "record") {
      if (request.method !== "POST") {
        return json({ error: "method_not_allowed" }, 405, { allow: "POST" });
      }
      const announced = await readAnnouncement(request);
      if (announced.error) return json({ error: announced.error }, announced.status);
      // Do nothing on a conflict, so an id that already counted stays counted exactly once and
      // the table needs no update grant. An empty returned set is how the provider says so.
      const { data, error } = await supabase.from("installs")
        .upsert(
          { install_id: announced.installId, version: announced.version, reported_at: new Date().toISOString() },
          { onConflict: "install_id", ignoreDuplicates: true },
        )
        .select("install_id");
      if (error || !Array.isArray(data)) return json({ error: "database_request_failed" }, 503);
      return json({ ok: true, action: "record", counted: data.length > 0 });
    }

    return json({ error: "not_found" }, 404);
  } catch {
    // Never pass a raw provider error to the browser.
    return json({ error: "database_request_failed" }, 503);
  }
}
