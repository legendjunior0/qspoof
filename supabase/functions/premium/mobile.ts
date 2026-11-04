import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const key = "sb_publishable_hi9tDuZNDMr8vF0vAwF5wg_s-XsIn6J";
const response = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), { status, headers: { ...headers, "Content-Type": "application/json" } });
const hash = async (value: string) => {
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
};

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers });
  if (request.method !== "POST") return response(405, { error: "Method not allowed" });
  if (request.headers.get("apikey") !== key) return response(401, { error: "Invalid API key" });
  const url = Deno.env.get("SUPABASE_URL");
  const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !service) return response(500, { error: "Premium service is not configured" });
  let body: Record<string, unknown>;
  try { body = await request.json(); } catch { return response(400, { error: "Invalid JSON body" }); }
  const db = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });
  const action = String(body.action || "");
  if (action === "health") return response(200, { ok: true, service: "premium", serverTime: new Date().toISOString() });
  const settings = async () => (await db.rpc("get_premium_public_settings")).data || {};
  if (action === "public-config") return response(200, await settings());
  if (action === "supported-devices-get") {
    const result = await db.rpc("get_app_supported_device_catalog");
    return result.error
      ? response(500, { error: "Could not load supported device catalog" })
      : response(200, { categories: result.data || [] });
  }
  if (action === "donors") {
    const [grants, requests] = await Promise.all([
      db.from("premium_reference_grants").select("transaction_id,display_name,display_name_partial,created_at,claimed_at").not("display_name", "is", null).limit(1000),
      db.from("premium_requests").select("transaction_id,display_name,display_name_partial,is_pinned,reviewed_at").eq("status", "approved").not("display_name", "is", null).limit(1000),
    ]);
    if (grants.error || requests.error) return response(500, { error: "Could not load donor names" });
    const all = [...(grants.data || []), ...(requests.data || [])];
    return response(200, { donors: all.map((item) => {
      const name = item.display_name || "";
      const chars = Array.from(name);
      return {
        displayName: item.display_name_partial && chars.length > 2
          ? `${chars[0]}${"*".repeat(chars.length - 2)}${chars[chars.length - 1]}`
          : name,
        isPinned: item.is_pinned === true,
        reviewedAt: item.claimed_at || item.reviewed_at || item.created_at,
      };
    }) });
  }

  const id = typeof body.androidId === "string" ? body.androidId.trim() : "";
  if (id.length < 8 || id.length > 128) return response(400, { error: "Invalid device identifier" });
  const hashResult = await db.rpc("hash_premium_device_id", { android_id: id });
  if (hashResult.error || typeof hashResult.data !== "string") {
    return response(500, { error: "Could not securely identify this device" });
  }
  const deviceHash = hashResult.data;

  if (action === "app-access") {
    const telemetry = typeof body.deviceModel === "string" && typeof body.cpu === "string" &&
      Number.isSafeInteger(body.ramMb) && Number.isSafeInteger(body.storageMb);
    const access = await db.rpc("check_app_startup", {
      p_android_id: id, p_model: telemetry ? body.deviceModel : null,
      p_ram_mb: telemetry ? body.ramMb : null, p_storage_mb: telemetry ? body.storageMb : null,
      p_cpu: telemetry ? body.cpu : null, p_ip_address: null,
      p_version_code: Number.isSafeInteger(body.versionCode) ? body.versionCode : 0,
    });
    if (access.error || !access.data) return response(500, { error: "Could not verify app access" });
    const config = await settings();
    return response(200, {
      ...access.data,
      latestVersionCode: config.latestVersionCode || 0,
      minimumVersionCode: config.minimumVersionCode || 0,
      updateUrl: config.updateUrl || "",
      updateMessage: config.updateMessage || "A new update is available.",
      channelUrl: config.channelUrl || "https://t.me/iqoo_neo10_channel",
      groupUrl: config.groupUrl || "https://t.me/iqoo_neo10",
      displayVersion: config.displayVersion || "",
      releaseNotes: config.releaseNotes || "",
      apkSha256: config.apkSha256 || "",
      apkStoragePath: config.apkStoragePath || "",
      remoteConfig: config.remoteConfig || {},
      popups: Array.isArray(config.popups) ? config.popups : [],
    });
  }
  if (action === "status") {
    const result = await db.from("premium_entitlements").select("granted_at").eq("device_hash", deviceHash).maybeSingle();
    if (result.error) return response(500, { error: "Could not check entitlement" });
    return response(200, { premium: result.data !== null });
  }
  if (action === "app-feedback-get") {
    const result = await db.rpc("get_app_feedback_vote", { p_device_hash: deviceHash });
    return result.error ? response(500, { error: "Could not load device feedback" }) : response(200, result.data || {});
  }
  if (action === "app-feedback-set") {
    if (typeof body.works !== "boolean") return response(400, { error: "Feedback must be yes or no" });
    const result = await db.rpc("set_app_feedback_vote", { p_device_hash: deviceHash, p_works: body.works });
    return result.error ? response(500, { error: "Could not save device feedback" }) : response(200, result.data || {});
  }
  if (action === "app-device-profile-get") {
    const result = await db.rpc("get_app_device_profile", { p_android_id: id });
    return result.error ? response(500, { error: "Could not load this device profile" }) : response(200, { profile: result.data });
  }
  if (action === "app-device-profile-set") {
    if (typeof body.name !== "string" || typeof body.email !== "string") return response(400, { error: "Enter a name and email address" });
    const result = await db.rpc("set_app_device_profile", { p_android_id: id, p_display_name: body.name, p_email: body.email });
    if (result.error || !result.data) return response(400, { error: result.error?.message || "Could not save profile" });
    return result.data.saved === false ? response(429, result.data) : response(200, { profile: result.data });
  }
  if (action === "device-usage") {
    const result = await db.rpc("record_app_device_usage", {
      p_device_hash: deviceHash, p_model: body.deviceModel, p_ram_mb: body.ramMb,
      p_storage_mb: body.storageMb, p_cpu: body.cpu,
    });
    return result.error ? response(500, { error: "Could not record device usage" }) : response(200, { recorded: true });
  }
  if (action === "self-delink") {
    const result = await db.rpc("delink_premium_device", { p_device_hash: deviceHash });
    return result.error ? response(500, { error: "Could not turn off premium on this device" }) : response(200, { delinked: result.data === true });
  }
  return response(400, { error: "This premium service does not support the requested app action" });
});
