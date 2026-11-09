import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const publicApiKey = "sb_publishable_hi9tDuZNDMr8vF0vAwF5wg_s-XsIn6J";
const masterAdminEmail = "adityakumar.as12345@gmail.com";

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

  async function getAdminProfile(): Promise<Record<string, unknown> | null> {
    const token = typeof body.adminToken === "string" ? body.adminToken : "";
    const browserId = typeof body.browserId === "string" ? body.browserId : "";
    if (!/^[0-9a-f]{64}$/.test(token) || browserId.length < 16 || browserId.length > 200) return null;
    const [sessionHash, browserHash] = await Promise.all([sha256Hex(token), sha256Hex(browserId)]);
    const { data: profile, error } = await supabase.rpc("get_premium_admin_session", {
      p_session_hash: sessionHash,
      p_browser_hash: browserHash,
      p_ip: getClientIp(request),
    });
    if (error || !profile || typeof profile.email !== "string") return null;
    return profile as Record<string, unknown>;
  }

  async function recordAdminAudit(profile: Record<string, unknown>): Promise<void> {
    const { error: auditError } = await supabase.rpc("record_premium_admin_audit", {
      p_actor: profile.email,
      p_action: action,
      p_target: null,
      p_ip: getClientIp(request),
    });
    if (auditError) console.warn("Admin audit write failed", auditError.message);
  }

  if (action === "health") {
    return jsonResponse(200, { ok: true, service: "premium-config" });
  }

  if (action === "public-config") {
    const settings = await getSettings();
    if (!settings) return jsonResponse(500, { error: "Could not load public app settings" });
    const storagePath = typeof settings.apkStoragePath === "string" ? settings.apkStoragePath : "";
    if (storagePath) {
      const downloadUrl = `${supabaseUrl.replace(/\/+$/, "")}/functions/v1/premium?action=update-download`;
      settings.updateUrl = downloadUrl;
      settings.apkDownloadUrl = downloadUrl;
    } else {
      settings.updateUrl = "";
      settings.apkDownloadUrl = "";
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
    "admin-update-popup-get": "manage_updates",
    "admin-update-popup-set": "manage_updates",
    "admin-popup-image-upload-url": "manage_updates",
    "admin-apk-upload-url": "manage_updates",
    "admin-apk-publish": "manage_updates",
  };
  const requiredPermission = permissions[action];
  if (!requiredPermission) return jsonResponse(400, { error: "Unsupported configuration action" });
  const adminProfile = await getAdminProfile();
  if (!adminProfile) {
    return jsonResponse(401, { error: "Admin session expired or browser verification failed. Sign in again." });
  }
  const profileEmail = typeof adminProfile.email === "string" ? adminProfile.email.toLowerCase() : "";
  const profilePermissions = Array.isArray(adminProfile.permissions) ? adminProfile.permissions : [];
  const isOwner = adminProfile.role === "owner" || profileEmail === masterAdminEmail;
  const permissionAliases: Record<string, string[]> = {
    manage_updates: ["manage_settings"],
    manage_app_access: ["manage_settings"],
    manage_ads: ["manage_settings"],
  };
  if (!isOwner && !profilePermissions.includes(requiredPermission) &&
      !(permissionAliases[requiredPermission] ?? []).some((alias) => profilePermissions.includes(alias))) {
    return jsonResponse(403, {
      error: `The ${requiredPermission.replaceAll("_", " ")} permission is required for ${profileEmail}.`,
    });
  }
  await recordAdminAudit(adminProfile);

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
      adsProvider: remoteConfig.adsProvider === "inmobi" ? "inmobi" : "startio",
      inmobiAccountId: typeof remoteConfig.inmobiAccountId === "string"
        ? remoteConfig.inmobiAccountId
        : "",
      inmobiInterstitialPlacementId: typeof remoteConfig.inmobiInterstitialPlacementId === "string"
        ? remoteConfig.inmobiInterstitialPlacementId
        : "",
      performanceBackgroundDelaySeconds: Number.isSafeInteger(
        remoteConfig.performanceBackgroundDelaySeconds,
      ) && Number(remoteConfig.performanceBackgroundDelaySeconds) > 0
        ? Number(remoteConfig.performanceBackgroundDelaySeconds)
        : 5,
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
    const adsProvider = body.adsProvider === "inmobi" ? "inmobi" : body.adsProvider === "startio"
      ? "startio"
      : null;
    const inmobiAccountId = typeof body.inmobiAccountId === "string"
      ? body.inmobiAccountId.trim()
      : "";
    const inmobiInterstitialPlacementId = typeof body.inmobiInterstitialPlacementId === "string"
      ? body.inmobiInterstitialPlacementId.trim()
      : "";
    const performanceBackgroundDelaySeconds = Number(body.performanceBackgroundDelaySeconds);
    if (!adsProvider) {
      return jsonResponse(400, { error: "Choose Start.io or InMobi as the ad provider." });
    }
    if (inmobiAccountId && !/^[A-Za-z0-9._-]{1,128}$/.test(inmobiAccountId)) {
      return jsonResponse(400, { error: "Enter a valid InMobi app/account ID." });
    }
    if (inmobiInterstitialPlacementId &&
        (!/^\d{1,19}$/.test(inmobiInterstitialPlacementId) ||
          BigInt(inmobiInterstitialPlacementId) > 9223372036854775807n)) {
      return jsonResponse(400, { error: "Enter an InMobi interstitial placement ID supported by Android." });
    }
    if (!Number.isSafeInteger(performanceBackgroundDelaySeconds) ||
        performanceBackgroundDelaySeconds <= 0 ||
        performanceBackgroundDelaySeconds > Math.floor(Number.MAX_SAFE_INTEGER / 1_000)) {
      return jsonResponse(400, { error: "Enter a positive whole number of seconds that the app can schedule safely." });
    }
    const { data, error } = await supabase.rpc("set_premium_ads_policy", { p_policy: body.adsPolicy });
    if (error || !data) return jsonResponse(400, { error: error?.message || "Could not update advertising policy" });
    const settings = await getSettings();
    if (!settings) {
      return jsonResponse(500, { error: "Advertising policy saved, but network settings could not be loaded." });
    }
    const currentRemoteConfig = settings.remoteConfig &&
      typeof settings.remoteConfig === "object" &&
      !Array.isArray(settings.remoteConfig)
      ? settings.remoteConfig as Record<string, unknown>
      : {};
    const remoteConfig = {
      ...currentRemoteConfig,
      adsPolicy: body.adsPolicy,
      adsProvider,
      inmobiAccountId,
      inmobiInterstitialPlacementId,
      performanceBackgroundDelaySeconds,
    };
    const { data: saved, error: configError } = await supabase.rpc("set_app_remote_config", {
      p_config: remoteConfig,
      p_display_version: typeof settings.displayVersion === "string" && settings.displayVersion
        ? settings.displayVersion
        : "1.0",
      p_release_notes: typeof settings.releaseNotes === "string" ? settings.releaseNotes : "",
      p_apk_sha256: typeof settings.apkSha256 === "string" ? settings.apkSha256 : "",
      p_apk_storage_path: typeof settings.apkStoragePath === "string" ? settings.apkStoragePath : "",
    });
    if (configError || !saved) {
      return jsonResponse(500, { error: "Advertising policy saved, but provider and delay settings could not be saved." });
    }
    const savedRemoteConfig = saved.remoteConfig && typeof saved.remoteConfig === "object"
      ? saved.remoteConfig as Record<string, unknown>
      : remoteConfig;
    return jsonResponse(200, {
      adsPolicy: savedRemoteConfig.adsPolicy,
      adsProvider,
      inmobiAccountId,
      inmobiInterstitialPlacementId,
      performanceBackgroundDelaySeconds,
      adsEnabled: saved.adsEnabled === true,
      premiumAdsEnabled: saved.premiumAdsEnabled === true,
      bannerSlotLimit: saved.bannerSlotLimit,
      interstitialsPerDay: saved.interstitialsPerDay,
    });
  }

  if (action === "app-popups-get") {
    const settings = await getSettings();
    if (!settings) return jsonResponse(500, { error: "Could not load popup settings" });
    return jsonResponse(200, { popups: Array.isArray(settings.popups) ? settings.popups : [] });
  }

  if (action === "admin-update-popup-get") {
    const settings = await getSettings();
    if (!settings) return jsonResponse(500, { error: "Could not load forced-update popup settings" });
    const remoteConfig = settings.remoteConfig && typeof settings.remoteConfig === "object"
      ? settings.remoteConfig as Record<string, unknown>
      : {};
    const popup = remoteConfig.updatePopup && typeof remoteConfig.updatePopup === "object"
      ? remoteConfig.updatePopup as Record<string, unknown>
      : {};
    return jsonResponse(200, {
      title: typeof popup.title === "string" ? popup.title : "Update required",
      message: typeof popup.message === "string"
        ? popup.message
        : "A new version is required to continue. Download and install the latest release.",
      imageUrl: typeof popup.imageUrl === "string" ? popup.imageUrl : "",
      downloadButtonText: typeof popup.downloadButtonText === "string" ? popup.downloadButtonText : "Download update",
      redirectUrl: typeof popup.redirectUrl === "string" ? popup.redirectUrl : "",
      redirectButtonText: typeof popup.redirectButtonText === "string" ? popup.redirectButtonText : "More info",
    });
  }

  if (action === "admin-update-popup-set") {
    const title = typeof body.title === "string" ? body.title.trim().slice(0, 100) : "";
    const message = typeof body.message === "string" ? body.message.trim().slice(0, 1000) : "";
    const imageUrl = typeof body.imageUrl === "string" ? body.imageUrl.trim().slice(0, 1000) : "";
    const downloadButtonText = typeof body.downloadButtonText === "string"
      ? body.downloadButtonText.trim().slice(0, 40)
      : "";
    const redirectUrl = typeof body.redirectUrl === "string" ? body.redirectUrl.trim().slice(0, 500) : "";
    const redirectButtonText = typeof body.redirectButtonText === "string"
      ? body.redirectButtonText.trim().slice(0, 40)
      : "";
    if (!title || !message || !downloadButtonText) {
      return jsonResponse(400, { error: "Enter a popup title, description, and download button text." });
    }
    if (redirectUrl) {
      try {
        if (new URL(redirectUrl).protocol !== "https:") throw new Error();
      } catch {
        return jsonResponse(400, { error: "The More info URL must be a valid HTTPS address." });
      }
      if (!redirectButtonText) {
        return jsonResponse(400, { error: "Enter the More info button text or clear its URL." });
      }
    }
    if (imageUrl) {
      let parsed: URL;
      try {
        parsed = new URL(imageUrl);
      } catch {
        return jsonResponse(400, { error: "The update popup image URL is invalid." });
      }
      const expectedOrigin = new URL(supabaseUrl).origin;
      if (parsed.origin !== expectedOrigin ||
          !/^\/storage\/v1\/object\/public\/app-promotions\/[0-9a-f-]+\.(jpg|png|webp)$/i.test(parsed.pathname) ||
          parsed.search || parsed.hash) {
        return jsonResponse(400, { error: "The update popup image must be uploaded to app-promotions." });
      }
    }

    const settings = await getSettings();
    if (!settings) return jsonResponse(500, { error: "Could not load app configuration." });
    const remoteConfig = settings.remoteConfig && typeof settings.remoteConfig === "object"
      ? { ...(settings.remoteConfig as Record<string, unknown>) }
      : {};
    const updatePopup = {
      title,
      message,
      imageUrl,
      downloadButtonText,
      redirectUrl,
      redirectButtonText: redirectUrl ? redirectButtonText : "",
    };
    remoteConfig.updatePopup = updatePopup;
    const { data, error } = await supabase.rpc("set_app_remote_config", {
      p_config: remoteConfig,
      p_display_version: typeof settings.displayVersion === "string" && settings.displayVersion
        ? settings.displayVersion
        : "1.0",
      p_release_notes: typeof settings.releaseNotes === "string" ? settings.releaseNotes : "",
      p_apk_sha256: typeof settings.apkSha256 === "string" ? settings.apkSha256 : "",
      p_apk_storage_path: typeof settings.apkStoragePath === "string" ? settings.apkStoragePath : "",
    });
    if (error || !data) {
      return jsonResponse(500, { error: error?.message || "Could not save forced-update popup settings." });
    }
    return jsonResponse(200, { updatePopup });
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
      ? { ...(settings.remoteConfig as Record<string, unknown>) }
      : {};
    remoteConfig.updateDeliveryMode = "supabase";
    const { data: release, error: releaseError } = await supabase.rpc("set_app_remote_config", {
      p_config: remoteConfig,
      p_display_version: displayVersion,
      p_release_notes: typeof body.releaseNotes === "string" ? body.releaseNotes.trim().slice(0, 2000) : "",
      p_apk_sha256: sha256,
      p_apk_storage_path: path,
    });
    if (releaseError || !release) return jsonResponse(500, { error: releaseError?.message || "Could not publish APK metadata." });
    const downloadUrl = `${supabaseUrl.replace(/\/+$/, "")}/functions/v1/premium?action=update-download`;
    const { error: updateError } = await supabase.rpc("set_app_update_config", {
      p_latest_version_code: latestVersionCode,
      p_minimum_version_code: minimumVersionCode,
      p_update_url: downloadUrl,
      p_update_message: typeof body.updateMessage === "string" ? body.updateMessage.trim().slice(0, 240) : "A new update is available.",
    });
    if (updateError) return jsonResponse(500, { error: "APK metadata was saved but version settings could not be published." });
    return jsonResponse(200, { published: true });
  }

  return jsonResponse(400, { error: "Unsupported configuration action" });
});
