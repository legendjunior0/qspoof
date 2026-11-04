import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const publicApiKey = "sb_publishable_hi9tDuZNDMr8vF0vAwF5wg_s-XsIn6J";

const jsonResponse = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });

async function sha256Hex(value: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
  return Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function getClientIp(request: Request): string | null {
  const value = (request.headers.get("cf-connecting-ip") ?? request.headers.get("x-real-ip") ?? "").trim();
  return value.length > 0 && value.length <= 45 ? value : null;
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return jsonResponse(405, { error: "Method not allowed" });
  if (request.headers.get("apikey") !== publicApiKey) return jsonResponse(401, { error: "Invalid API key" });

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !serviceRoleKey) return jsonResponse(500, { error: "Configuration service is not configured" });

  let body: Record<string, unknown>;
  try {
    const parsed: unknown = await request.json();
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return jsonResponse(400, { error: "Invalid request body" });
    }
    body = parsed as Record<string, unknown>;
  } catch {
    return jsonResponse(400, { error: "Invalid JSON body" });
  }

  const supabase = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const action = typeof body.action === "string" ? body.action : "";

  async function getSettings(): Promise<Record<string, unknown> | null> {
    const { data, error } = await supabase.rpc("get_premium_public_settings");
    if (error || !data || typeof data !== "object") return null;
    return data as Record<string, unknown>;
  }

  async function authorize(permission: string): Promise<boolean> {
    const token = typeof body.adminToken === "string" ? body.adminToken : "";
    const browserId = typeof body.browserId === "string" ? body.browserId : "";
    if (!/^[0-9a-f]{64}$/.test(token) || browserId.length < 16 || browserId.length > 200) return false;
    const [sessionHash, browserHash] = await Promise.all([sha256Hex(token), sha256Hex(browserId)]);
    const { data: profile, error } = await supabase.rpc("get_premium_admin_session", {
      p_session_hash: sessionHash,
      p_browser_hash: browserHash,
      p_ip: getClientIp(request),
    });
    if (error || !profile || typeof profile.email !== "string") return false;
    const permissions = Array.isArray(profile.permissions) ? profile.permissions : [];
    const aliases: Record<string, string[]> = {
      manage_updates: ["manage_settings"],
      manage_app_access: ["manage_settings"],
      manage_ads: ["manage_settings"],
    };
    if (profile.role !== "owner" && !permissions.includes(permission) &&
        !(aliases[permission] ?? []).some((alias) => permissions.includes(alias))) return false;
    const { error: auditError } = await supabase.rpc("record_premium_admin_audit", {
      p_actor: profile.email,
      p_action: action,
      p_target: null,
      p_ip: getClientIp(request),
    });
    if (auditError) console.warn("Admin audit write failed", auditError.message);
    return true;
  }

  if (action === "health") {
    return jsonResponse(200, { ok: true, service: "premium-config" });
  }

  if (action === "public-config") {
    const settings = await getSettings();
    if (!settings) return jsonResponse(500, { error: "Could not load public app settings" });
    const storagePath = typeof settings.apkStoragePath === "string" ? settings.apkStoragePath : "";
    if (storagePath) {
      const { data, error } = await supabase.storage.from("app-updates").createSignedUrl(storagePath, 3600);
      if (error || !data?.signedUrl) return jsonResponse(500, { error: "Could not prepare the update download" });
      settings.apkDownloadUrl = data.signedUrl;
    }
    return jsonResponse(200, settings);
  }

  const permissions: Record<string, string> = {
    "admin-app-access-get": "manage_app_access",
    "admin-app-access-set": "manage_app_access",
    "admin-ads-get": "manage_ads",
    "admin-ads-config-set": "manage_ads",
    "app-popups-get": "manage_updates",
    "app-popups-set": "manage_updates",
    "admin-popup-image-upload-url": "manage_updates",
    "admin-apk-upload-url": "manage_updates",
    "admin-apk-publish": "manage_updates",
  };
  const requiredPermission = permissions[action];
  if (!requiredPermission) return jsonResponse(400, { error: "Unsupported configuration action" });
  if (!await authorize(requiredPermission)) return jsonResponse(403, { error: "The required permission for this control is missing." });

  if (action === "admin-app-access-get") {
    const settings = await getSettings();
    if (!settings || typeof settings.premiumOnlyAccess !== "boolean") {
      return jsonResponse(500, { error: "Could not load app access settings" });
    }
    return jsonResponse(200, {
      premiumOnlyAccess: settings.premiumOnlyAccess,
      normalAccessCheckIntervalHours: settings.normalAccessCheckIntervalHours ?? 6,
      premiumAccessCheckIntervalHours: settings.premiumAccessCheckIntervalHours ?? 6,
    });
  }

  if (action === "admin-app-access-set") {
    const normalInterval = Number(body.normalAccessCheckIntervalHours);
    const premiumInterval = Number(body.premiumAccessCheckIntervalHours);
    if (typeof body.premiumOnlyAccess !== "boolean" ||
        !Number.isSafeInteger(normalInterval) || normalInterval < 1 || normalInterval > 168 ||
        !Number.isSafeInteger(premiumInterval) || premiumInterval < 1 || premiumInterval > 168) {
      return jsonResponse(400, { error: "Choose valid access intervals from 1 to 168 hours" });
    }
    const { data, error } = await supabase.rpc("set_app_access_policy", {
      p_premium_only_access: body.premiumOnlyAccess,
      p_normal_access_check_interval_hours: normalInterval,
      p_premium_access_check_interval_hours: premiumInterval,
    });
    if (error || !data) return jsonResponse(500, { error: error?.message || "Could not update access settings" });
    return jsonResponse(200, {
      premiumOnlyAccess: data.premiumOnlyAccess,
      normalAccessCheckIntervalHours: data.normalAccessCheckIntervalHours,
      premiumAccessCheckIntervalHours: data.premiumAccessCheckIntervalHours,
    });
  }

  if (action === "admin-ads-get") {
    const settings = await getSettings();
    if (!settings) return jsonResponse(500, { error: "Could not load ad settings" });
    const remoteConfig = settings.remoteConfig && typeof settings.remoteConfig === "object"
      ? settings.remoteConfig as Record<string, unknown>
      : {};
    return jsonResponse(200, {
      adsPolicy: remoteConfig.adsPolicy,
      adsEnabled: settings.adsEnabled === true,
      premiumAdsEnabled: settings.premiumAdsEnabled === true,
      bannerSlotLimit: settings.bannerSlotLimit,
      interstitialsPerDay: settings.interstitialsPerDay,
    });
  }

  if (action === "admin-ads-config-set") {
    if (!body.adsPolicy || typeof body.adsPolicy !== "object" || Array.isArray(body.adsPolicy)) {
      return jsonResponse(400, { error: "Enter a valid advertising policy" });
    }
    const { data, error } = await supabase.rpc("set_premium_ads_policy", { p_policy: body.adsPolicy });
    if (error || !data) return jsonResponse(400, { error: error?.message || "Could not update advertising policy" });
    const remoteConfig = data.remoteConfig && typeof data.remoteConfig === "object"
      ? data.remoteConfig as Record<string, unknown>
      : {};
    return jsonResponse(200, {
      adsPolicy: remoteConfig.adsPolicy,
      adsEnabled: data.adsEnabled === true,
      premiumAdsEnabled: data.premiumAdsEnabled === true,
      bannerSlotLimit: data.bannerSlotLimit,
      interstitialsPerDay: data.interstitialsPerDay,
    });
  }

  if (action === "app-popups-get") {
    const settings = await getSettings();
    if (!settings) return jsonResponse(500, { error: "Could not load popup settings" });
    return jsonResponse(200, { popups: Array.isArray(settings.popups) ? settings.popups : [] });
  }

  if (action === "app-popups-set") {
    if (!Array.isArray(body.popups) || body.popups.length > 20) {
      return jsonResponse(400, { error: "Add at most 20 popups." });
    }
    let popups: Record<string, unknown>[];
    try {
      popups = body.popups.map((popup) => {
        if (!popup || typeof popup !== "object" || Array.isArray(popup)) throw new Error("Invalid popup entry");
        const item = popup as Record<string, unknown>;
        const title = typeof item.title === "string" ? item.title.trim().slice(0, 100) : "";
        const message = typeof item.message === "string" ? item.message.trim().slice(0, 1000) : "";
        const link = typeof item.link === "string" ? item.link.trim().slice(0, 500) : "";
        const buttonText = typeof item.buttonText === "string" ? item.buttonText.trim().slice(0, 40) : "";
        const buttonMode = item.buttonMode === "download" ? "download" : "link";
        const imageUrl = typeof item.imageUrl === "string" ? item.imageUrl.trim().slice(0, 1000) : "";
        const maxDisplays = Number.isSafeInteger(item.maxDisplays) ? Number(item.maxDisplays) : 1;
        const cooldownHours = Number.isSafeInteger(item.cooldownHours) ? Number(item.cooldownHours) : 0;
        if (!title || !message || maxDisplays < 0 || maxDisplays > 100 || cooldownHours < 0 || cooldownHours > 720) {
          throw new Error("Each popup needs a title, message, and valid display limits.");
        }
        if (link) {
          try {
            if (new URL(link).protocol !== "https:") throw new Error();
          } catch {
            throw new Error("Popup destinations must be valid HTTPS URLs.");
          }
        }
        if (imageUrl) {
          let parsed: URL;
          try {
            parsed = new URL(imageUrl);
          } catch {
            throw new Error("Popup image URL is invalid.");
          }
          const expectedOrigin = new URL(supabaseUrl).origin;
          if (parsed.origin !== expectedOrigin ||
              !/^\/storage\/v1\/object\/public\/app-promotions\/[0-9a-f-]+\.(jpg|png|webp)$/i.test(parsed.pathname) ||
              parsed.search || parsed.hash) {
            throw new Error("Popup images must be uploaded to the app-promotions storage bucket.");
          }
        }
        return {
          id: typeof item.id === "string" && item.id.trim() ? item.id.trim().slice(0, 80) : crypto.randomUUID(),
          enabled: item.enabled === true,
          title,
          message,
          link,
          buttonText,
          buttonMode,
          imageUrl,
          dismissible: item.dismissible !== false,
          maxDisplays,
          cooldownHours,
        };
      });
    } catch (error) {
      return jsonResponse(400, { error: error instanceof Error ? error.message : "Invalid popup configuration" });
    }
    const { data, error } = await supabase.rpc("set_app_popups", { p_popups: popups });
    if (error || !data) return jsonResponse(400, { error: error?.message || "Could not save popups" });
    return jsonResponse(200, { popups: data });
  }

  if (action === "admin-popup-image-upload-url") {
    const fileSize = Number(body.fileSize);
    const contentType = typeof body.contentType === "string" ? body.contentType : "";
    const extension = ({ "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp" } as Record<string, string>)[contentType];
    if (!Number.isSafeInteger(fileSize) || fileSize < 1 || fileSize > 8 * 1024 * 1024 || !extension) {
      return jsonResponse(400, { error: "Upload a JPEG, PNG, or WebP image no larger than 8 MB." });
    }
    const path = `${crypto.randomUUID()}.${extension}`;
    const { data, error } = await supabase.storage.from("app-promotions").createSignedUploadUrl(path);
    if (error || !data?.signedUrl || !data?.token) {
      return jsonResponse(500, { error: "Could not prepare promotion image upload." });
    }
    const { data: publicObject } = supabase.storage.from("app-promotions").getPublicUrl(path);
    return jsonResponse(200, { path, signedUrl: data.signedUrl, token: data.token, publicUrl: publicObject.publicUrl });
  }

  if (action === "admin-apk-upload-url") {
    const fileName = typeof body.fileName === "string" ? body.fileName.trim() : "";
    const fileSize = Number(body.fileSize);
    if (!/^[A-Za-z0-9._-]{1,120}\.apk$/i.test(fileName) ||
        !Number.isSafeInteger(fileSize) || fileSize < 1 || fileSize > 200 * 1024 * 1024) {
      return jsonResponse(400, { error: "Upload an APK file no larger than 200 MB." });
    }
    const path = `${crypto.randomUUID()}-${fileName}`;
    const { data, error } = await supabase.storage.from("app-updates").createSignedUploadUrl(path);
    if (error || !data?.signedUrl || !data?.token) return jsonResponse(500, { error: "Could not prepare APK upload." });
    return jsonResponse(200, { path, signedUrl: data.signedUrl, token: data.token });
  }

  if (action === "admin-apk-publish") {
    const path = typeof body.path === "string" ? body.path.trim() : "";
    const sha256 = typeof body.sha256 === "string" ? body.sha256.trim().toLowerCase() : "";
    const latestVersionCode = Number(body.latestVersionCode);
    const minimumVersionCode = Number(body.minimumVersionCode);
    const displayVersion = typeof body.displayVersion === "string" ? body.displayVersion.trim() : "";
    if (!/^([0-9a-f-]{36})-[A-Za-z0-9._-]{1,120}\.apk$/i.test(path) ||
        !/^[0-9a-f]{64}$/.test(sha256) ||
        !Number.isSafeInteger(latestVersionCode) || !Number.isSafeInteger(minimumVersionCode) ||
        latestVersionCode < 1 || minimumVersionCode < 0 || minimumVersionCode > latestVersionCode ||
        !/^[^<>]{1,32}$/.test(displayVersion)) {
      return jsonResponse(400, { error: "Enter valid APK release metadata." });
    }
    const settings = await getSettings();
    if (!settings) return jsonResponse(500, { error: "Could not load app configuration" });
    const remoteConfig = settings.remoteConfig && typeof settings.remoteConfig === "object"
      ? settings.remoteConfig as Record<string, unknown>
      : {};
    const { data: release, error: releaseError } = await supabase.rpc("set_app_remote_config", {
      p_config: remoteConfig,
      p_display_version: displayVersion,
      p_release_notes: typeof body.releaseNotes === "string" ? body.releaseNotes.trim().slice(0, 2000) : "",
      p_apk_sha256: sha256,
      p_apk_storage_path: path,
    });
    if (releaseError || !release) return jsonResponse(500, { error: releaseError?.message || "Could not publish APK metadata." });
    const { data: signedDownload, error: signedError } = await supabase.storage
      .from("app-updates")
      .createSignedUrl(path, 31536000);
    if (signedError || !signedDownload?.signedUrl) return jsonResponse(500, { error: "Could not prepare the published APK download." });
    const { error: updateError } = await supabase.rpc("set_app_update_config", {
      p_latest_version_code: latestVersionCode,
      p_minimum_version_code: minimumVersionCode,
      p_update_url: signedDownload.signedUrl,
      p_update_message: typeof body.updateMessage === "string" ? body.updateMessage.trim().slice(0, 240) : "A new update is available.",
    });
    if (updateError) return jsonResponse(500, { error: "APK metadata was saved but version settings could not be published." });
    return jsonResponse(200, { published: true });
  }

  return jsonResponse(400, { error: "Unsupported configuration action" });
});
