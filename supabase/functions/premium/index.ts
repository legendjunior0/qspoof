import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, range, if-range",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

const jsonResponse = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });

const publicApiKey = "sb_publishable_hi9tDuZNDMr8vF0vAwF5wg_s-XsIn6J";
const masterAdminEmail = "adityakumar.as12345@gmail.com";
const defaultMonthlyBandwidthGb = 10;
const updateDownloadBandwidthShare = 0.4;
const motherLoginEmail = "adityakumar.as12345@email.com";
const motherAccountEmail = "adityakumar.as12345@gmail.com";
const motherLoginAliases = new Set([motherLoginEmail, motherAccountEmail]);

function getClientIp(request: Request): string | null {
  const candidate = (
    request.headers.get("cf-connecting-ip") ?? request.headers.get("x-real-ip") ?? ""
  ).trim();
  if (!candidate || candidate.length > 45) return null;

  const ipv4Parts = candidate.split(".");
  if (
    ipv4Parts.length === 4 &&
    ipv4Parts.every((part) => /^\d{1,3}$/.test(part) && Number(part) <= 255)
  ) return candidate;

  if (!/^[0-9a-f:]+$/i.test(candidate) || !candidate.includes(":")) return null;
  try {
    return new URL(`http://[${candidate}]/`).hostname.slice(1, -1).toLowerCase();
  } catch {
    return null;
  }
}

const supportedUpdatePackages = new Set(["com.antutu.ABenchMark", "com.ludashi.benchmark"]);

function updateDownloadEndpoint(
  supabaseUrl: string,
  browserLink = false,
  packageId?: string,
): string {
  const endpoint = `${supabaseUrl.replace(/\/+$/, "")}/functions/v1/premium?action=update-download`;
  const query = new URLSearchParams();
  if (packageId) query.set("packageId", packageId);
  if (browserLink) query.set("apikey", publicApiKey);
  const suffix = query.toString();
  return suffix ? `${endpoint}&${suffix}` : endpoint;
}

function getPackageRelease(
  remoteConfig: Record<string, unknown>,
  packageId: string,
): Record<string, unknown> | null {
  const releases = remoteConfig.packageReleases;
  if (!releases || typeof releases !== "object" || Array.isArray(releases)) return null;
  const release = (releases as Record<string, unknown>)[packageId];
  return release && typeof release === "object" && !Array.isArray(release)
    ? release as Record<string, unknown>
    : null;
}

function updateFallbackResponse(fallbackUrl: unknown, status: number, message: string): Response {
  if (typeof fallbackUrl === "string" && fallbackUrl.startsWith("https://")) {
    return Response.redirect(fallbackUrl, 302);
  }
  return new Response(message, {
    status,
    headers: {
      "Cache-Control": "no-store",
      "Content-Type": "text/plain; charset=utf-8",
    },
  });
}

async function serveMeteredUpdateDownload(
  request: Request,
  supabase: ReturnType<typeof createClient>,
  supabaseUrl: string,
): Promise<Response> {
  const { data: settings, error: settingsError } = await supabase.rpc("get_premium_public_settings");
  if (settingsError || !settings || typeof settings !== "object") {
    console.error("Could not load metered update settings", settingsError?.message);
    return new Response("Update download is temporarily unavailable.", {
      status: 503,
      headers: { "Cache-Control": "no-store" },
    });
  }
  const config = settings as Record<string, unknown>;
  const remoteConfig = config.remoteConfig && typeof config.remoteConfig === "object"
    ? config.remoteConfig as Record<string, unknown>
    : {};
  const requestUrl = new URL(request.url);
  const requestedPackageId = requestUrl.searchParams.get("packageId");
  if (requestedPackageId && !supportedUpdatePackages.has(requestedPackageId)) {
    return new Response("Unsupported app package.", {
      status: 400,
      headers: { "Cache-Control": "no-store" },
    });
  }
  const packageId = requestedPackageId ?? "com.antutu.ABenchMark";
  const packageRelease = getPackageRelease(remoteConfig, packageId);
  const usesLegacyRelease = requestedPackageId === null ||
    (packageId === "com.antutu.ABenchMark" && packageRelease === null);
  const selectedRelease = packageRelease ?? (usesLegacyRelease ? config : null);
  if (!selectedRelease) {
    return new Response("No release is published for this app package.", {
      status: 404,
      headers: { "Cache-Control": "no-store" },
    });
  }
  const packageFallbackUrl = packageRelease?.updateFallbackUrl;
  const fallbackUrl = typeof packageFallbackUrl === "string" && packageFallbackUrl
    ? packageFallbackUrl
    : remoteConfig.updateFallbackUrl;
  const storagePath = typeof selectedRelease.apkStoragePath === "string"
    ? selectedRelease.apkStoragePath
    : "";
  if (!storagePath) {
    return updateFallbackResponse(fallbackUrl, 404, "No Supabase APK is published.");
  }

  const configuredBudgetGb = remoteConfig.monthlyBandwidthLimitGb;
  const monthlyBandwidthGb = typeof configuredBudgetGb === "number" &&
      Number.isFinite(configuredBudgetGb) &&
      configuredBudgetGb >= 1 &&
      configuredBudgetGb <= 10000
    ? configuredBudgetGb
    : defaultMonthlyBandwidthGb;
  const monthlyLimitBytes = Math.floor(monthlyBandwidthGb * 1_000_000_000);
  const downloadLimitBytes = Math.floor(monthlyLimitBytes * updateDownloadBandwidthShare);
  const { data: signed, error: signedError } = await supabase.storage
    .from("app-updates")
    .createSignedUrl(storagePath, 120);
  if (signedError || !signed?.signedUrl) {
    console.error("Could not create metered update URL", signedError?.message);
    return updateFallbackResponse(fallbackUrl, 502, "Could not prepare the Supabase APK download.");
  }

  let metadata: Response;
  try {
    metadata = await fetch(signed.signedUrl, { method: "HEAD" });
  } catch (error) {
    console.error("Could not read Supabase APK metadata", error);
    return updateFallbackResponse(fallbackUrl, 502, "Could not read Supabase APK metadata.");
  }
  const fileSize = Number(metadata.headers.get("content-length"));
  if (!metadata.ok || !Number.isSafeInteger(fileSize) || fileSize <= 0) {
    return updateFallbackResponse(fallbackUrl, 502, "Could not determine Supabase APK size.");
  }

  let reservedBytes = fileSize;
  const range = request.headers.get("range");
  if (range) {
    const match = /^bytes=(\d*)-(\d*)$/i.exec(range.trim());
    if (match) {
      const start = match[1] ? Number(match[1]) : null;
      const end = match[2] ? Number(match[2]) : null;
      if (start === null && end !== null) {
        reservedBytes = Math.min(end, fileSize);
      } else if (start !== null && start < fileSize) {
        reservedBytes = Math.max(0, Math.min(end ?? (fileSize - 1), fileSize - 1) - start + 1);
      }
    }
  }
  if (reservedBytes <= 0) {
    return new Response("Invalid byte range.", {
      status: 416,
      headers: { "Cache-Control": "no-store" },
    });
  }

  const { data: reserved, error: reservationError } = await supabase.rpc(
    "reserve_app_update_bandwidth",
    {
      p_requested_bytes: reservedBytes,
      p_monthly_limit_bytes: downloadLimitBytes,
    },
  );
  if (reservationError) {
    console.error("Could not reserve APK bandwidth", reservationError.message);
    return updateFallbackResponse(fallbackUrl, 503, "Could not check the APK bandwidth allowance.");
  }
  if (reserved !== true) {
    return updateFallbackResponse(fallbackUrl, 429, "Supabase APK bandwidth allowance reached.");
  }

  const downloadHeaders = new Headers();
  if (range) downloadHeaders.set("Range", range);
  const ifRange = request.headers.get("if-range");
  if (ifRange) downloadHeaders.set("If-Range", ifRange);
  let download: Response;
  try {
    download = await fetch(signed.signedUrl, {
      headers: downloadHeaders,
      signal: request.signal,
    });
  } catch (error) {
    console.error("Supabase APK transfer failed", error);
    return updateFallbackResponse(fallbackUrl, 502, "Supabase APK transfer failed.");
  }
  if (!download.ok || !download.body) {
    return updateFallbackResponse(fallbackUrl, 502, "Supabase APK transfer failed.");
  }
  if (range && download.status === 200 && reservedBytes < fileSize) {
    const { data: fullFileReserved, error: fullFileReservationError } = await supabase.rpc(
      "reserve_app_update_bandwidth",
      {
        p_requested_bytes: fileSize - reservedBytes,
        p_monthly_limit_bytes: downloadLimitBytes,
      },
    );
    if (fullFileReservationError || fullFileReserved !== true) {
      await download.body.cancel();
      return updateFallbackResponse(fallbackUrl, 429, "Supabase APK bandwidth allowance reached.");
    }
  }

  const headers = new Headers({
    "Cache-Control": "no-store",
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Disposition": 'attachment; filename="qspoof-update.apk"',
    "Accept-Ranges": "bytes",
    "X-Content-Type-Options": "nosniff",
  });
  for (const headerName of ["content-length", "content-range", "etag", "last-modified"]) {
    const value = download.headers.get(headerName);
    if (value) headers.set(headerName, value);
  }
  return new Response(download.body, {
    status: download.status,
    headers,
  });
}

async function authorizeAdmin(
  supabase: ReturnType<typeof createClient>,
  token: unknown,
  permission?: string,
  action = "admin-action",
  targetEmail: unknown = null,
  request?: Request,
  browserHash?: string,
): Promise<Record<string, unknown> | null> {
  if (typeof token !== "string" || !/^[0-9a-f]{64}$/.test(token)) return null;
  const sessionHash = await sha256Hex(token);
  const { data: profile, error } = await supabase.rpc("get_premium_admin_session", {
    p_session_hash: sessionHash,
    p_browser_hash: browserHash ?? "",
    p_ip: request ? getClientIp(request) : null,
  });
  if (error || !profile || typeof profile.email !== "string") return null;
  const permissions = Array.isArray(profile.permissions) ? profile.permissions : [];
  const legacyPermissionAliases: Record<string, string[]> = {
    manage_active_premium: ["manage_premium"], view_active_premium: ["manage_active_premium", "manage_premium"],
    view_premium_references: ["add_premium_references", "remove_premium_references", "manage_premium"],
    add_premium_references: ["manage_premium"], remove_premium_references: ["manage_premium"],
    grant_email_premium: ["manage_premium"],
    clear_premium_data: ["manage_premium"], manage_ads: ["manage_settings"],
    manage_app_access: ["manage_settings"], manage_updates: ["manage_settings"],
    manage_links: ["manage_settings"], manage_supported_devices: ["manage_settings"],
    view_devices: ["view_analytics"],
  };
  const isOwner = profile.role === "owner" ||
    (typeof profile.email === "string" && profile.email.toLowerCase() === masterAdminEmail);
  if (permission && !isOwner && !permissions.includes(permission) &&
      !(legacyPermissionAliases[permission] || []).some((alias) => permissions.includes(alias))) return null;
  const { error: auditError } = await supabase.rpc("record_premium_admin_audit", {
    p_actor: profile.email,
    p_action: action,
    p_target: typeof targetEmail === "string" ? targetEmail : null,
    p_ip: request ? getClientIp(request) : null,
  });
  if (auditError) console.warn("Admin audit write failed", auditError.message);
  return profile as Record<string, unknown>;
}


async function createRegistrationToken(secret: string, browserHash: string): Promise<string> {
  const expires = Date.now() + 30 * 60 * 1000;
  const payload = `qspoof-admin-registration:${expires}:${browserHash}`;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(payload)));
  const hex = Array.from(sig, b => b.toString(16).padStart(2, "0")).join("");
  return `${expires}.${browserHash}.${hex}`;
}
async function verifyRegistrationToken(token: unknown, secret: string, browserHash: string): Promise<boolean> {
  if (typeof token !== "string" || !browserHash) return false;
  const [expiresText, boundHash, signature] = token.split(".");
  const expires = Number(expiresText);
  if (!Number.isSafeInteger(expires) || expires < Date.now() || expires > Date.now() + 30 * 60 * 1000 || boundHash !== browserHash || !/^[0-9a-f]{64}$/.test(signature || "")) return false;
  const payload = `qspoof-admin-registration:${expires}:${browserHash}`;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  const bytes = new Uint8Array((signature.match(/../g) || []).map(v => parseInt(v, 16)));
  return await crypto.subtle.verify("HMAC", key, bytes, new TextEncoder().encode(payload));
}

async function sha256Hex(value: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
  return Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function newAdminToken(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(32)), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

const ADMIN_PERMISSION_KEYS = [
  "review_payments","view_active_premium","manage_active_premium",
  "view_premium_references","add_premium_references","remove_premium_references",
  "grant_email_premium","clear_premium_data","manage_ads","manage_app_access",
  "view_analytics","view_devices","manage_updates","manage_supported_devices","manage_links",
] as const;

const DISPOSABLE_EMAIL_DOMAINS = new Set([
  "mailinator.com","guerrillamail.com","guerrillamail.net","guerrillamail.org",
  "10minutemail.com","10minutemail.net","temp-mail.org","tempmail.com","tempmail.net",
  "yopmail.com","yopmail.fr","getnada.com","maildrop.cc","dispostable.com",
  "throwawaymail.com","trashmail.com","trashmail.net","sharklasers.com","grr.la",
  "mohmal.com","emailondeck.com","fakeinbox.com","minuteinbox.com","mintemail.com",
  "discard.email","discardmail.com","mailnesia.com","mytemp.email","tmpmail.org","tmpmail.net",
  "harakirimail.com","inboxkitten.com","burnermail.io","mailsac.com",
]);

function isDisposableAdminEmail(email: string): boolean {
  const domain = email.split("@")[1]?.toLowerCase() || "";
  return !domain || [...DISPOSABLE_EMAIL_DOMAINS].some((blocked) =>
    domain === blocked || domain.endsWith("." + blocked)
  );
}

function permissionForAction(action: string): string | null {
  if (["updates-get","updates-set"].includes(action)) return "manage_updates";
  if (["app-popups-get","app-popups-set","admin-apk-upload-url","admin-apk-publish","admin-popup-image-upload-url"].includes(action)) return "manage_updates";
  if (["links-get","links-set"].includes(action)) return "manage_links";
  if (["admin-ads-get","admin-ads-set","admin-ads-config-set"].includes(action)) return "manage_ads";
  if (["admin-app-access-get","admin-app-access-set"].includes(action)) return "manage_app_access";
  if (action === "admin-premium-list") return "view_premium_references";
  if (action === "admin-premium-add") return "add_premium_references";
  if (action === "admin-premium-remove") return "remove_premium_references";
  if (action === "admin-email-premium-add") return "grant_email_premium";
  if (action === "admin-clear-data") return "clear_premium_data";
  if (action === "admin-list-active") return "view_active_premium";
  if (["admin-revoke","admin-unlink-reference"].includes(action)) return "manage_active_premium";
  if (["admin-approve","admin-approve-reference","admin-reject","admin-list"].includes(action)) return "review_payments";
  if (["admin-analytics","admin-analytics-period-devices"].includes(action)) return "view_analytics";
  if (action === "admin-device-inventory") return "view_devices";
  if (["admin-supported-devices-get","admin-supported-devices-set"].includes(action)) return "manage_supported_devices";
  return null;
}

async function createAdminSession(
  supabase: ReturnType<typeof createClient>,
  email: string,
  browserHash: string,
  request: Request,
  trustBrowser: boolean,
) {
  const token = newAdminToken();
  if (!/^[0-9a-f]{64}$/.test(browserHash)) return null;
  const sessionHash = await sha256Hex(token);
  const { data: profile, error } = await supabase.rpc("create_premium_admin_session", {
    p_email: email,
    p_session_hash: sessionHash,
    p_browser_hash: browserHash,
    p_ip: getClientIp(request),
    p_trust_browser: trustBrowser,
  });
  if (error || !profile) return null;
  const deviceLabel = (request.headers.get("user-agent") || "Browser session").slice(0, 120);
  await supabase.rpc("set_premium_admin_session_label", {
    p_session_hash: sessionHash,
    p_device_label: deviceLabel,
  });
  return { token, profile };
}

async function authorizeAdminPassword(
  supabase: ReturnType<typeof createClient>,
  email: string,
  password: string,
): Promise<boolean> {
  if (!email || !password || password.length > 256) return false;
  const { data, error } = await supabase.rpc("verify_premium_admin_password", {
    candidate_email: email,
    candidate_password: password,
  });
  return !error && data === true;
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  const isUpdateDownload = request.method === "GET" &&
    new URL(request.url).searchParams.get("action") === "update-download";
  if (request.method !== "POST" && !isUpdateDownload) {
    return jsonResponse(405, { error: "Method not allowed" });
  }
  const requestUrl = new URL(request.url);
  const suppliedApiKey = request.headers.get("apikey") ||
    (isUpdateDownload ? requestUrl.searchParams.get("apikey") : null);
  if (suppliedApiKey !== publicApiKey) {
    return jsonResponse(401, { error: "Invalid API key" });
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !serviceRoleKey) {
    return jsonResponse(500, { error: "Premium service is not configured" });
  }

  const supabase = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  if (isUpdateDownload) {
    return serveMeteredUpdateDownload(request, supabase, supabaseUrl);
  }

  let body: {
    action?: unknown;
    transactionId?: unknown;
    androidId?: unknown;
    requestId?: unknown;
    adminEmail?: unknown;
    gateEmail?: unknown;
    gatePassword?: unknown;
    adminPassword?: unknown;
    adminToken?: unknown;
    registrationToken?: unknown;
    browserId?: unknown;
    adminName?: unknown;
    currentPassword?: unknown;
    bootstrapPassword?: unknown;
    setupMode?: unknown;
    displayName?: unknown;
    showNamePartially?: unknown;
    showName?: unknown;
    adsEnabled?: unknown;
    premiumOnlyAccess?: unknown;
    normalAccessCheckIntervalHours?: unknown;
    premiumAccessCheckIntervalHours?: unknown;
    adsPolicy?: unknown;
    adsProvider?: unknown;
    inmobiAccountId?: unknown;
    inmobiInterstitialPlacementId?: unknown;
    performanceBackgroundDelaySeconds?: unknown;
    deviceModel?: unknown;
    ramMb?: unknown;
    storageMb?: unknown;
    cpu?: unknown;
    newEmail?: unknown;
    newPassword?: unknown;
    targetEmail?: unknown;
    permissions?: unknown;
    versionCode?: unknown;
    latestVersionCode?: unknown;
    minimumVersionCode?: unknown;
    packageId?: unknown;
    updateUrl?: unknown;
    updateFallbackUrl?: unknown;
    deliveryMode?: unknown;
    enforceMinimumVersion?: unknown;
    redirectUrl?: unknown;
    monthlyBandwidthLimitGb?: unknown;
    updateMessage?: unknown;
    channelUrl?: unknown;
    groupUrl?: unknown;
    name?: unknown;
    email?: unknown;
    otpCode?: unknown;
    categories?: unknown;
    period?: unknown;
    periodStart?: unknown;
    sessionId?: unknown;
    popups?: unknown;
    fileName?: unknown;
    fileSize?: unknown;
    contentType?: unknown;
    path?: unknown;
    sha256?: unknown;
    displayVersion?: unknown;
    releaseNotes?: unknown;
    works?: unknown;
  };
  try {
    body = await request.json();
  } catch {
    return jsonResponse(400, { error: "Invalid JSON body" });
  }

  // Accept the password-login action name used by older browser admin panels.
  // Admin authentication is independent of Android app device identifiers.
  if (body.action === "admin-login" || body.action === "admin-login-password") {
    body.action = "admin-login-start";
  }

  const browserId = typeof body.browserId === "string" && body.browserId.length >= 16 && body.browserId.length <= 200
    ? body.browserId
    : "";
  const browserHash = browserId ? await sha256Hex(browserId) : "";

  if (body.action === "admin-entry-gate") {
    const gateEmail = typeof body.gateEmail === "string" ? body.gateEmail.trim().toLowerCase() : "";
    const gatePassword = typeof body.gatePassword === "string" ? body.gatePassword : "";
    if (gateEmail !== "admin@email.com" || gatePassword !== "passforadmin") {
      return jsonResponse(401, { error: "Incorrect entry credentials." });
    }
    const masterEmail = motherAccountEmail;
    const { data: masterState, error: masterStateError } = await supabase.rpc(
      "get_premium_admin_password_state", { p_email: masterEmail },
    );
    if (masterStateError || !masterState) {
      return jsonResponse(500, { error: "Mother administrator account is unavailable." });
    }
    const masterCreated = masterState.passwordInitialized !== true;
    // One-time bootstrap only. This does not create a login session and never overwrites an initialized password.
    if (masterState.passwordInitialized !== true) {
      const { data: initialized, error: initializeError } = await supabase.rpc(
        "set_qspoof_master_password", { p_password: "1234", p_name: "Aditya Kumar" },
      );
      if (initializeError) {
        return jsonResponse(500, { error: "Could not initialize the mother administrator account." });
      }
    }
    if (!browserHash) return jsonResponse(400, { error: "Could not bind this registration to the current browser. Refresh and retry." });
    return jsonResponse(200, {
      ready: true,
      masterInitialized: true,
      masterCreated,
      registrationToken: await createRegistrationToken(serviceRoleKey, browserHash),
    });
  }

  if (body.action === "health") {
    return jsonResponse(200, { ok: true, service: "premium", serverTime: new Date().toISOString() });
  }

  // All Android client actions carry the installed version. Keep this check
  // server-side so an outdated client cannot bypass the app's startup screen.
  const mobileActions = new Set([
    "app-access",
    "public-config",
    "donors",
    "submit",
    "status",
    "self-delink",
    "app-email-otp-send",
    "app-email-otp-verify",
    "app-device-profile-get",
    "app-device-profile-set",
    "device-usage",
    "supported-devices-get",
  ]);
  const minimumSupportedVersion = 9;
  const requestedPackageId = typeof body.packageId === "string" ? body.packageId : null;
  if (requestedPackageId && !supportedUpdatePackages.has(requestedPackageId)) {
    return jsonResponse(400, { error: "Unsupported app package." });
  }
  const appPackageId = requestedPackageId ?? "com.antutu.ABenchMark";
  if (mobileActions.has(String(body.action))) {
    const versionValid = typeof body.versionCode === "number" &&
      Number.isSafeInteger(body.versionCode) &&
      body.versionCode >= minimumSupportedVersion;
    if (versionValid && body.action !== "app-access") {
      const { data: settings, error: settingsError } =
        await supabase.rpc("get_premium_public_settings");
      if (settingsError || !settings || typeof settings !== "object") {
        console.error("Could not load mobile version policy", settingsError?.message);
        return jsonResponse(503, { error: "Could not verify the app version policy." });
      }
      const remoteConfig = settings.remoteConfig && typeof settings.remoteConfig === "object"
        ? settings.remoteConfig as Record<string, unknown>
        : {};
      const packageRelease = getPackageRelease(remoteConfig, appPackageId);
      const legacyRelease = appPackageId === "com.antutu.ABenchMark" && packageRelease === null;
      const selectedRelease = packageRelease ?? (legacyRelease ? settings : null);
      const releaseMinimum = Number.isSafeInteger(selectedRelease?.minimumVersionCode)
        ? Number(selectedRelease.minimumVersionCode)
        : Number.isSafeInteger(settings.minimumVersionCode)
          ? Number(settings.minimumVersionCode)
          : minimumSupportedVersion;
      const releaseLatest = Number.isSafeInteger(selectedRelease?.latestVersionCode)
        ? Number(selectedRelease.latestVersionCode)
        : Number.isSafeInteger(settings.latestVersionCode)
          ? Number(settings.latestVersionCode)
          : Math.max(releaseMinimum, minimumSupportedVersion);
      const minimumVersionRequired = remoteConfig.enforceMinimumVersion === true
        ? releaseLatest
        : releaseMinimum;
      if (Number(body.versionCode) < Math.max(minimumSupportedVersion, minimumVersionRequired)) {
        return jsonResponse(426, {
          error: "A newer version is required. Update the app to continue.",
          minimumVersionCode: Math.max(minimumSupportedVersion, minimumVersionRequired),
          latestVersionCode: releaseLatest,
          updateUrl: selectedRelease?.apkStoragePath
            ? updateDownloadEndpoint(supabaseUrl, false, appPackageId)
            : "",
          updateFallbackUrl: selectedRelease?.updateFallbackUrl ?? remoteConfig.updateFallbackUrl ?? "",
        });
      }
    }
    if (!versionValid) {
      const { data: settings } = await supabase.rpc("get_premium_public_settings");
      const configuredMinimum = Number.isSafeInteger(settings?.minimumVersionCode)
        ? Number(settings.minimumVersionCode)
        : minimumSupportedVersion;
      const configuredLatest = Number.isSafeInteger(settings?.latestVersionCode)
        ? Number(settings.latestVersionCode)
        : minimumSupportedVersion;
      const minimumVersionCode = Math.max(minimumSupportedVersion, configuredMinimum);
      const latestVersionCode = Math.max(minimumVersionCode, configuredLatest);
      const remoteConfig = settings?.remoteConfig && typeof settings.remoteConfig === "object"
        ? settings.remoteConfig as Record<string, unknown>
        : {};
      const packageRelease = getPackageRelease(remoteConfig, appPackageId);
      const legacyRelease = appPackageId === "com.antutu.ABenchMark" && packageRelease === null;
      const selectedRelease = packageRelease ?? (legacyRelease ? settings : null);
      const releaseMinimum = Number.isSafeInteger(selectedRelease?.minimumVersionCode)
        ? Number(selectedRelease.minimumVersionCode)
        : minimumVersionCode;
      const releaseLatest = Number.isSafeInteger(selectedRelease?.latestVersionCode)
        ? Number(selectedRelease.latestVersionCode)
        : Math.max(releaseMinimum, latestVersionCode);
      const minimumVersionRequired = remoteConfig.enforceMinimumVersion === true
        ? releaseLatest
        : releaseMinimum;
      const storagePath = typeof selectedRelease?.apkStoragePath === "string"
        ? selectedRelease.apkStoragePath
        : "";
      const downloadUrl = storagePath
        ? updateDownloadEndpoint(supabaseUrl, false, appPackageId)
        : "";
      const updateMessage = typeof settings?.updateMessage === "string"
        ? settings.updateMessage
        : "A newer version is required to continue.";
      const channelUrl = typeof settings?.channelUrl === "string"
        ? settings.channelUrl
        : "https://t.me/iqoo_neo10_channel";
      const groupUrl = typeof settings?.groupUrl === "string"
        ? settings.groupUrl
        : "https://t.me/iqoo_neo10";
      if (body.action === "app-access") {
        return jsonResponse(200, {
          premium: false,
          accessAllowed: false,
          adsEnabled: settings?.adsEnabled === true,
          minimumVersionCode: minimumVersionRequired,
          latestVersionCode: releaseLatest,
          updateUrl: downloadUrl,
          updateFallbackUrl: typeof selectedRelease?.updateFallbackUrl === "string" &&
              selectedRelease.updateFallbackUrl
            ? selectedRelease.updateFallbackUrl
            : typeof remoteConfig.updateFallbackUrl === "string"
              ? remoteConfig.updateFallbackUrl
            : "",
          apkSha256: typeof selectedRelease?.apkSha256 === "string"
            ? selectedRelease.apkSha256
            : "",
          updateMessage: typeof selectedRelease?.updateMessage === "string"
            ? selectedRelease.updateMessage
            : updateMessage,
          channelUrl,
          groupUrl,
          versionRequired: true,
        });
      }
      return jsonResponse(426, {
        error: "A newer version is required. Update the app to continue.",
        minimumVersionCode: minimumVersionRequired,
        latestVersionCode: releaseLatest,
        updateUrl: downloadUrl,
        channelUrl,
      });
    }
  }

  if (body.action === "public-config") {
    const { data, error } = await supabase.rpc("get_premium_public_settings");
    if (error) return jsonResponse(500, { error: "Could not load public settings" });
    if (!data || typeof data.adsEnabled !== "boolean") {
      return jsonResponse(500, { error: "Public settings are not configured" });
    }
    if (typeof data.premiumOnlyAccess !== "boolean") {
      return jsonResponse(500, { error: "App access settings are not configured" });
    }
    const publicSettings = { ...(data as Record<string, unknown>) };
    const hasPublishedApk =
      typeof publicSettings.apkStoragePath === "string" && publicSettings.apkStoragePath.length > 0;
    publicSettings.updateUrl = "";
    publicSettings.apkDownloadUrl = "";
    if (hasPublishedApk) {
      const downloadUrl = updateDownloadEndpoint(supabaseUrl);
      publicSettings.updateUrl = downloadUrl;
      publicSettings.apkDownloadUrl = downloadUrl;
    }
    return jsonResponse(200, publicSettings);
  }

  if (["updates-get", "updates-set", "links-get", "links-set", "app-popups-get", "app-popups-set"].includes(String(body.action))) {
    const requiredPermission = permissionForAction(String(body.action));
    if (!requiredPermission || !await authorizeAdmin(supabase, body.adminToken, requiredPermission, String(body.action), null, request, browserHash)) {
      return jsonResponse(403, { error: "The required permission for this section is missing." });
    }
    if (String(body.action).endsWith("-get")) {
      const { data, error } = await supabase.rpc("get_premium_public_settings");
      if (error || !data) return jsonResponse(500, { error: "Could not load app settings" });
      if (body.action === "updates-get" && typeof data === "object") {
        const settings = data as Record<string, unknown>;
        const remoteConfig = settings.remoteConfig && typeof settings.remoteConfig === "object"
          ? settings.remoteConfig as Record<string, unknown>
          : {};
        settings.enforceMinimumVersion = remoteConfig.enforceMinimumVersion === true;
        const packageReleases = remoteConfig.packageReleases &&
          typeof remoteConfig.packageReleases === "object" &&
          !Array.isArray(remoteConfig.packageReleases)
          ? remoteConfig.packageReleases as Record<string, unknown>
          : {};
        settings.packageReleases = packageReleases;
        settings.packageDownloadUrls = Object.fromEntries(
          [...supportedUpdatePackages].map((packageId) => [
            packageId,
            getPackageRelease(remoteConfig, packageId)?.apkStoragePath
              ? updateDownloadEndpoint(supabaseUrl, true, packageId)
              : "",
          ]),
        );
        const storagePath = typeof settings.apkStoragePath === "string" ? settings.apkStoragePath : "";
        settings.updateUrl = storagePath
          ? updateDownloadEndpoint(supabaseUrl, true, "com.antutu.ABenchMark")
          : "";
        settings.updateFallbackUrl = typeof remoteConfig.updateFallbackUrl === "string"
          ? remoteConfig.updateFallbackUrl
          : "";
        const monthlyBandwidthLimitGb = typeof remoteConfig.monthlyBandwidthLimitGb === "number" &&
            Number.isFinite(remoteConfig.monthlyBandwidthLimitGb) &&
            remoteConfig.monthlyBandwidthLimitGb >= 1 &&
            remoteConfig.monthlyBandwidthLimitGb <= 10000
          ? remoteConfig.monthlyBandwidthLimitGb
          : defaultMonthlyBandwidthGb;
        const { data: reservedBytes, error: bandwidthError } = await supabase.rpc(
          "get_app_update_bandwidth_usage",
        );
        if (bandwidthError) {
          return jsonResponse(500, { error: "Could not load APK bandwidth usage." });
        }
        settings.monthlyBandwidthLimitGb = monthlyBandwidthLimitGb;
        settings.updateBandwidthReservedBytes = Number(reservedBytes) || 0;
      }
      return jsonResponse(200, data);
    }
    if (body.action === "app-popups-get") {
      const { data, error } = await supabase.rpc("get_premium_public_settings");
      if (error || !data) return jsonResponse(500, { error: "Could not load popup settings" });
      return jsonResponse(200, { popups: Array.isArray(data.popups) ? data.popups : [] });
    }
    let rpc: string;
    let args: Record<string, unknown>;
    if (body.action === "app-popups-set") {
      if (!Array.isArray(body.popups) || body.popups.length > 20) {
        return jsonResponse(400, { error: "Add at most 20 popups." });
      }
      let popups: Record<string, unknown>[];
      try {
        popups = body.popups.map((popup) => {
          if (!popup || typeof popup !== "object") throw new Error("Invalid popup entry");
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
              if (new URL(link).protocol !== "https:") {
                throw new Error("Popup destinations must use HTTPS.");
              }
            } catch {
              throw new Error("Popup destinations must be valid HTTPS URLs.");
            }
          }
          if (imageUrl) {
            let parsedImageUrl: URL;
            try {
              parsedImageUrl = new URL(imageUrl);
            } catch {
              throw new Error("Popup image URL is invalid.");
            }
            const expectedOrigin = new URL(supabaseUrl).origin;
            if (parsedImageUrl.origin !== expectedOrigin ||
                !/^\/storage\/v1\/object\/public\/app-promotions\/[0-9a-f-]+\.(jpg|png|webp)$/i.test(parsedImageUrl.pathname) ||
                parsedImageUrl.search || parsedImageUrl.hash) {
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
        return jsonResponse(400, {
          error: error instanceof Error ? error.message : "Invalid popup configuration",
        });
      }
      rpc = "set_app_popups";
      args = { p_popups: popups };
    } else if (body.action === "updates-set") {
      if (!Number.isSafeInteger(body.latestVersionCode) || !Number.isSafeInteger(body.minimumVersionCode) ||
          typeof body.updateUrl !== "string" || typeof body.updateMessage !== "string") {
        return jsonResponse(400, { error: "Enter valid version codes, update link, and message" });
      }
      if (body.enforceMinimumVersion !== undefined &&
          typeof body.enforceMinimumVersion !== "boolean") {
        return jsonResponse(400, { error: "Choose whether to enforce the latest app version." });
      }
      if (body.enforceMinimumVersion === true) {
        const { data: releaseSettings, error: releaseSettingsError } =
          await supabase.rpc("get_premium_public_settings");
        if (releaseSettingsError || !releaseSettings || typeof releaseSettings !== "object") {
          return jsonResponse(500, { error: "Could not verify both package releases before enforcing the update." });
        }
        const savedRemoteConfig = releaseSettings.remoteConfig &&
          typeof releaseSettings.remoteConfig === "object" &&
          !Array.isArray(releaseSettings.remoteConfig)
          ? releaseSettings.remoteConfig as Record<string, unknown>
          : {};
        const packageReleases = savedRemoteConfig.packageReleases &&
          typeof savedRemoteConfig.packageReleases === "object" &&
          !Array.isArray(savedRemoteConfig.packageReleases)
          ? savedRemoteConfig.packageReleases as Record<string, unknown>
          : {};
        const bothPackagesPublished = [...supportedUpdatePackages].every((packageId) => {
          const release = packageReleases[packageId];
          if (!release || typeof release !== "object" || Array.isArray(release)) return false;
          const releaseRecord = release as Record<string, unknown>;
          return typeof releaseRecord.apkStoragePath === "string" &&
            releaseRecord.apkStoragePath.length > 0 &&
            releaseRecord.latestVersionCode === body.latestVersionCode;
        });
        if (!bothPackagesPublished) {
          return jsonResponse(400, {
            error: "Publish the same latest build for both Antutu and Ludashi before enforcing it.",
          });
        }
      }
      if (body.deliveryMode !== undefined &&
          body.deliveryMode !== "external" && body.deliveryMode !== "supabase") {
        return jsonResponse(400, { error: "Choose Supabase APK upload or an external HTTPS link." });
      }
      if (body.updateUrl && (() => {
        try {
          return new URL(body.updateUrl).protocol !== "https:";
        } catch {
          return true;
        }
      })()) {
        return jsonResponse(400, { error: "The update destination must be a valid HTTPS URL." });
      }
      if (body.deliveryMode === "external" && !body.updateUrl) {
        return jsonResponse(400, { error: "Enter the external HTTPS update destination." });
      }
      const updateFallbackUrl = typeof body.updateFallbackUrl === "string"
        ? body.updateFallbackUrl.trim()
        : "";
      if (updateFallbackUrl && (() => {
        try {
          return new URL(updateFallbackUrl).protocol !== "https:";
        } catch {
          return true;
        }
      })()) {
        return jsonResponse(400, { error: "The external fallback must be a valid HTTPS direct APK URL." });
      }
      const redirectUrl = typeof body.redirectUrl === "string" ? body.redirectUrl.trim() : "";
      if (body.redirectUrl !== undefined && (!redirectUrl || (() => {
        try {
          return new URL(redirectUrl).protocol !== "https:";
        } catch {
          return true;
        }
      })())) {
        return jsonResponse(400, { error: "The after-download redirect must be a valid HTTPS URL." });
      }
      if (body.monthlyBandwidthLimitGb !== undefined &&
          (typeof body.monthlyBandwidthLimitGb !== "number" ||
            !Number.isFinite(body.monthlyBandwidthLimitGb) ||
            body.monthlyBandwidthLimitGb < 1 ||
            body.monthlyBandwidthLimitGb > 10000)) {
        return jsonResponse(400, { error: "Project bandwidth must be between 1 and 10,000 GB." });
      }
      rpc = "set_app_update_config";
      args = { p_latest_version_code: body.latestVersionCode, p_minimum_version_code: body.minimumVersionCode,
        p_update_url: body.updateUrl, p_update_message: body.updateMessage };
    } else {
      if (typeof body.channelUrl !== "string" || typeof body.groupUrl !== "string") {
        return jsonResponse(400, { error: "Enter valid channel and group links" });
      }
      rpc = "set_app_telegram_links";
      args = { p_channel_url: body.channelUrl, p_group_url: body.groupUrl };
    }
    const { data, error } = await supabase.rpc(rpc, args);
    if (error || !data) return jsonResponse(400, { error: error?.message || "Could not save settings" });
    if (body.action === "updates-set" && body.redirectUrl !== undefined) {
      const settings = data as Record<string, unknown>;
      const groupUrl = typeof settings.groupUrl === "string" ? settings.groupUrl : "";
      if (!groupUrl) {
        return jsonResponse(500, { error: "Version policy saved, but the existing discussion link could not be loaded." });
      }
      const { data: links, error: linksError } = await supabase.rpc("set_app_telegram_links", {
        p_channel_url: typeof body.redirectUrl === "string" ? body.redirectUrl.trim() : "",
        p_group_url: groupUrl,
      });
      if (linksError || !links) {
        return jsonResponse(500, { error: "Version policy saved, but the after-download redirect could not be saved." });
      }
      const savedLinks = links as Record<string, unknown>;
      data.channelUrl = savedLinks.channelUrl;
    }
    if (body.action === "updates-set" &&
        (body.deliveryMode || body.enforceMinimumVersion !== undefined)) {
      const { data: current, error: settingsError } = await supabase.rpc("get_premium_public_settings");
      if (settingsError || !current || typeof current !== "object") {
        return jsonResponse(500, { error: "Version policy saved, but delivery mode could not be loaded." });
      }
      const settings = current as Record<string, unknown>;
      const remoteConfig = settings.remoteConfig && typeof settings.remoteConfig === "object"
        ? { ...(settings.remoteConfig as Record<string, unknown>) }
        : {};
      if (body.deliveryMode) remoteConfig.updateDeliveryMode = body.deliveryMode;
      const enforceMinimumVersion = typeof body.enforceMinimumVersion === "boolean"
        ? body.enforceMinimumVersion
        : remoteConfig.enforceMinimumVersion === true;
      remoteConfig.enforceMinimumVersion = enforceMinimumVersion;
      remoteConfig.updateFallbackUrl = typeof body.updateFallbackUrl === "string"
        ? body.updateFallbackUrl.trim()
        : typeof remoteConfig.updateFallbackUrl === "string"
          ? remoteConfig.updateFallbackUrl
          : "";
      remoteConfig.monthlyBandwidthLimitGb = typeof body.monthlyBandwidthLimitGb === "number"
        ? body.monthlyBandwidthLimitGb
        : typeof remoteConfig.monthlyBandwidthLimitGb === "number"
          ? remoteConfig.monthlyBandwidthLimitGb
          : defaultMonthlyBandwidthGb;
      const { error: configError } = await supabase.rpc("set_app_remote_config", {
        p_config: remoteConfig,
        p_display_version: typeof settings.displayVersion === "string" && settings.displayVersion
          ? settings.displayVersion
          : "1.0",
        p_release_notes: typeof settings.releaseNotes === "string" ? settings.releaseNotes : "",
        p_apk_sha256: typeof settings.apkSha256 === "string" ? settings.apkSha256 : "",
        p_apk_storage_path: typeof settings.apkStoragePath === "string" ? settings.apkStoragePath : "",
      });
      if (configError) {
        return jsonResponse(500, { error: "Version policy saved, but download source could not be saved." });
      }
      const saved = data && typeof data === "object" ? data as Record<string, unknown> : {};
      return jsonResponse(200, {
        ...saved,
        deliveryMode: remoteConfig.updateDeliveryMode,
        enforceMinimumVersion,
        updateFallbackUrl: remoteConfig.updateFallbackUrl,
        monthlyBandwidthLimitGb: remoteConfig.monthlyBandwidthLimitGb,
        redirectUrl: data.channelUrl,
      });
    }
    return jsonResponse(200, data);
  }

  if (body.action === "admin-apk-upload-url" || body.action === "admin-apk-publish" ||
      body.action === "admin-popup-image-upload-url") {
    const actor = await authorizeAdmin(supabase, body.adminToken, "manage_updates", String(body.action), null, request, browserHash);
    if (!actor) return jsonResponse(403, { error: "Update-management permission required." });
    if (body.action === "admin-popup-image-upload-url") {
      const fileSize = Number(body.fileSize);
      const contentType = typeof body.contentType === "string" ? body.contentType : "";
      const extensionByType: Record<string, string> = {
        "image/jpeg": "jpg",
        "image/png": "png",
        "image/webp": "webp",
      };
      const extension = extensionByType[contentType];
      if (!Number.isSafeInteger(fileSize) || fileSize < 1 || fileSize > 8 * 1024 * 1024 || !extension) {
        return jsonResponse(400, { error: "Upload a JPEG, PNG, or WebP image no larger than 8 MB." });
      }
      const path = `${crypto.randomUUID()}.${extension}`;
      const { data, error } = await supabase.storage
        .from("app-promotions")
        .createSignedUploadUrl(path);
      if (error || !data?.signedUrl || !data?.token) {
        return jsonResponse(500, { error: "Could not prepare promotion image upload." });
      }
      const { data: publicObject } = supabase.storage.from("app-promotions").getPublicUrl(path);
      return jsonResponse(200, {
        path,
        signedUrl: data.signedUrl,
        token: data.token,
        publicUrl: publicObject.publicUrl,
      });
    }
    if (body.action === "admin-apk-upload-url") {
      const packageId = typeof body.packageId === "string" ? body.packageId : "";
      if (!supportedUpdatePackages.has(packageId)) {
        return jsonResponse(400, { error: "Select a supported app package." });
      }
      const fileName = typeof body.fileName === "string" ? body.fileName.trim() : "";
      const fileSize = Number(body.fileSize);
      if (!/^[A-Za-z0-9._-]{1,120}\.apk$/i.test(fileName) || !Number.isSafeInteger(fileSize) || fileSize < 1 || fileSize > 200 * 1024 * 1024) {
        return jsonResponse(400, { error: "Upload an APK file no larger than 200 MB." });
      }
      const path = `${crypto.randomUUID()}-${fileName}`;
      const { data, error } = await supabase.storage.from("app-updates").createSignedUploadUrl(path);
      if (error || !data?.signedUrl || !data?.token) return jsonResponse(500, { error: "Could not prepare APK upload." });
      return jsonResponse(200, { path, signedUrl: data.signedUrl, token: data.token });
    }
    const path = typeof body.path === "string" ? body.path.trim() : "";
    const packageId = typeof body.packageId === "string" ? body.packageId : "";
    const sha256 = typeof body.sha256 === "string" ? body.sha256.trim().toLowerCase() : "";
    const latestVersionCode = Number(body.latestVersionCode);
    const minimumVersionCode = Number(body.minimumVersionCode);
    const displayVersion = typeof body.displayVersion === "string" ? body.displayVersion.trim() : "";
    const updateFallbackUrl = typeof body.updateFallbackUrl === "string"
      ? body.updateFallbackUrl.trim()
      : "";
    if (!supportedUpdatePackages.has(packageId) ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[A-Za-z0-9._-]{1,120}\.apk$/i.test(path) ||
        !/^[0-9a-f]{64}$/.test(sha256) ||
        !Number.isSafeInteger(latestVersionCode) || !Number.isSafeInteger(minimumVersionCode) ||
        latestVersionCode < 1 || minimumVersionCode < 0 || minimumVersionCode > latestVersionCode ||
        !/^[^<>]{1,32}$/.test(displayVersion)) {
      return jsonResponse(400, { error: "Enter valid APK release metadata." });
    }
    if (updateFallbackUrl) {
      try {
        if (new URL(updateFallbackUrl).protocol !== "https:" || updateFallbackUrl.length > 500) {
          throw new Error("Invalid fallback URL");
        }
      } catch {
        return jsonResponse(400, { error: "The package fallback must be a valid HTTPS APK URL." });
      }
    }
    const { data: currentSettings, error: settingsError } = await supabase.rpc("get_premium_public_settings");
    if (settingsError || !currentSettings) {
      return jsonResponse(500, { error: "Could not load existing release settings." });
    }
    const currentRemoteConfig = currentSettings.remoteConfig &&
      typeof currentSettings.remoteConfig === "object" &&
      !Array.isArray(currentSettings.remoteConfig)
      ? currentSettings.remoteConfig as Record<string, unknown>
      : {};
    const packageReleases = currentRemoteConfig.packageReleases &&
      typeof currentRemoteConfig.packageReleases === "object" &&
      !Array.isArray(currentRemoteConfig.packageReleases)
      ? currentRemoteConfig.packageReleases as Record<string, unknown>
      : {};
    packageReleases[packageId] = {
      latestVersionCode,
      minimumVersionCode,
      displayVersion,
      releaseNotes: typeof body.releaseNotes === "string" ? body.releaseNotes.slice(0, 2000) : "",
      updateMessage: typeof body.updateMessage === "string"
        ? body.updateMessage.slice(0, 240)
        : "A new update is available.",
      updateFallbackUrl,
      apkSha256: sha256,
      apkStoragePath: path,
    };
    const isAntutuRelease = packageId === "com.antutu.ABenchMark";
    const { data, error } = await supabase.rpc("set_app_remote_config", {
      p_config: { ...currentRemoteConfig, packageReleases },
      p_display_version: isAntutuRelease
        ? displayVersion
        : String(currentSettings.displayVersion || displayVersion),
      p_release_notes: isAntutuRelease
        ? typeof body.releaseNotes === "string" ? body.releaseNotes.slice(0, 2000) : ""
        : String(currentSettings.releaseNotes ?? ""),
      p_apk_sha256: isAntutuRelease ? sha256 : String(currentSettings.apkSha256 ?? ""),
      p_apk_storage_path: isAntutuRelease ? path : String(currentSettings.apkStoragePath ?? ""),
    });
    if (error || !data) return jsonResponse(500, { error: error?.message || "Could not publish APK release." });
    if (isAntutuRelease) {
      const { data: updated, error: updateError } = await supabase.rpc("set_app_update_config", {
        p_latest_version_code: latestVersionCode,
        p_minimum_version_code: minimumVersionCode,
        p_update_url: updateDownloadEndpoint(supabaseUrl),
        p_update_message: typeof body.updateMessage === "string"
          ? body.updateMessage.slice(0, 240)
          : "A new update is available.",
      });
      if (updateError || !updated) return jsonResponse(500, { error: "APK uploaded but release settings could not be published." });
    }
    return jsonResponse(200, { published: true, settings: data });
  }

  if (
    body.action === "admin-ads-get" ||
    body.action === "admin-ads-set" ||
    body.action === "admin-ads-config-set" ||
    body.action === "admin-app-access-get" ||
    body.action === "admin-app-access-set" ||
    body.action === "admin-premium-list" ||
    body.action === "admin-premium-add" ||
    body.action === "admin-email-premium-add" ||
    body.action === "admin-premium-remove" ||
    body.action === "admin-clear-data"
  ) {
    const requiredPermission = permissionForAction(String(body.action));
    if (!requiredPermission || !await authorizeAdmin(supabase, body.adminToken, requiredPermission, String(body.action), body.targetEmail, request, browserHash)) {
      return jsonResponse(403, { error: "The required permission for this control is missing." });
    }

    if (body.action === "admin-ads-get") {
      const { data, error } = await supabase.rpc("get_premium_public_settings");
      if (error) return jsonResponse(500, { error: "Could not load ad settings" });
      const remoteConfig = data?.remoteConfig && typeof data.remoteConfig === "object"
        ? data.remoteConfig as Record<string, unknown>
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
        adsEnabled: data?.adsEnabled === true,
        premiumAdsEnabled: data?.premiumAdsEnabled === true,
        bannerSlotLimit: data?.bannerSlotLimit,
        interstitialsPerDay: data?.interstitialsPerDay,
      });
    }

    if (body.action === "admin-ads-set") {
      if (typeof body.adsEnabled !== "boolean") {
        return jsonResponse(400, { error: "Choose whether ads are enabled" });
      }
      const { data, error } = await supabase.rpc(
        "set_premium_ads_enabled",
        { enabled: body.adsEnabled },
      );
      if (error) return jsonResponse(500, { error: "Could not update ad settings" });
      return jsonResponse(200, { adsEnabled: data === true });
    }

    if (body.action === "admin-ads-config-set") {
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
      const { data, error } = await supabase.rpc("set_premium_ads_policy", {
        p_policy: body.adsPolicy,
      });
      if (error || !data) {
        return jsonResponse(400, { error: error?.message || "Could not update advertising policy" });
      }
      const { data: current, error: settingsError } = await supabase.rpc(
        "get_premium_public_settings",
      );
      if (settingsError || !current) {
        return jsonResponse(500, { error: "Advertising policy saved, but network settings could not be loaded." });
      }
      const currentRemoteConfig = current.remoteConfig &&
        typeof current.remoteConfig === "object" &&
        !Array.isArray(current.remoteConfig)
        ? current.remoteConfig as Record<string, unknown>
        : {};
      const remoteConfig = {
        ...currentRemoteConfig,
        adsPolicy: data.adsPolicy ?? body.adsPolicy,
        adsProvider,
        inmobiAccountId,
        inmobiInterstitialPlacementId,
        performanceBackgroundDelaySeconds,
      };
      const { data: savedSettings, error: configError } = await supabase.rpc(
        "set_app_remote_config",
        {
          p_config: remoteConfig,
          p_display_version: typeof current.displayVersion === "string" && current.displayVersion
            ? current.displayVersion
            : "1.0",
          p_release_notes: typeof current.releaseNotes === "string" ? current.releaseNotes : "",
          p_apk_sha256: typeof current.apkSha256 === "string" ? current.apkSha256 : "",
          p_apk_storage_path: typeof current.apkStoragePath === "string"
            ? current.apkStoragePath
            : "",
        },
      );
      if (configError || !savedSettings) {
        return jsonResponse(500, { error: "Advertising policy saved, but provider and delay settings could not be saved." });
      }
      const savedRemoteConfig = savedSettings.remoteConfig &&
        typeof savedSettings.remoteConfig === "object"
        ? savedSettings.remoteConfig as Record<string, unknown>
        : remoteConfig;
      return jsonResponse(200, {
        adsPolicy: savedRemoteConfig.adsPolicy,
        adsProvider,
        inmobiAccountId,
        inmobiInterstitialPlacementId,
        performanceBackgroundDelaySeconds,
        adsEnabled: savedSettings.adsEnabled === true,
        premiumAdsEnabled: savedSettings.premiumAdsEnabled === true,
        bannerSlotLimit: savedSettings.bannerSlotLimit,
        interstitialsPerDay: savedSettings.interstitialsPerDay,
      });
    }

    if (body.action === "admin-app-access-get") {
      const { data, error } = await supabase.rpc("get_premium_public_settings");
      if (error || typeof data?.premiumOnlyAccess !== "boolean") {
        return jsonResponse(500, { error: "Could not load premium-only access setting" });
      }
      return jsonResponse(200, {
        premiumOnlyAccess: data.premiumOnlyAccess,
        normalAccessCheckIntervalHours: data.normalAccessCheckIntervalHours ?? 6,
        premiumAccessCheckIntervalHours: data.premiumAccessCheckIntervalHours ?? 6,
      });
    }

    if (body.action === "admin-app-access-set") {
      const normalInterval = Number(body.normalAccessCheckIntervalHours);
      const premiumInterval = Number(body.premiumAccessCheckIntervalHours);
      if (typeof body.premiumOnlyAccess !== "boolean" ||
          !Number.isSafeInteger(normalInterval) || normalInterval < 1 || normalInterval > 168 ||
          !Number.isSafeInteger(premiumInterval) || premiumInterval < 1 || premiumInterval > 168) {
        return jsonResponse(400, { error: "Choose valid access intervals from 1 to 168 hours" });
      }
      const { data, error } = await supabase.rpc(
        "set_app_access_policy",
        {
          p_premium_only_access: body.premiumOnlyAccess,
          p_normal_access_check_interval_hours: normalInterval,
          p_premium_access_check_interval_hours: premiumInterval,
        },
      );
      if (error || typeof data?.premiumOnlyAccess !== "boolean") {
        return jsonResponse(500, { error: error?.message || "Could not update access settings" });
      }
      return jsonResponse(200, {
        premiumOnlyAccess: data.premiumOnlyAccess,
        normalAccessCheckIntervalHours: data.normalAccessCheckIntervalHours,
        premiumAccessCheckIntervalHours: data.premiumAccessCheckIntervalHours,
      });
    }

    if (body.action === "admin-premium-list") {
      const { data, error } = await supabase
        .from("premium_reference_grants")
        .select("transaction_id, display_name, display_name_partial, created_at, claimed_at")
        .order("created_at", { ascending: false })
        .limit(500);
      if (error) return jsonResponse(500, { error: "Could not load manual premium references" });
      return jsonResponse(200, { references: data ?? [] });
    }

    if (body.action === "admin-email-premium-add") {
      const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
      if (email.length > 254 || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
        return jsonResponse(400, { error: "Enter a valid email address" });
      }
      const { data, error } = await supabase.rpc("grant_app_premium_by_verified_email", { p_email: email });
      if (error) return jsonResponse(400, { error: error.message });
      if (!data || data.granted !== true) return jsonResponse(409, { error: data?.error || "Could not grant premium by email" });
      return jsonResponse(200, { grant: data });
    }

  if (body.action === "admin-premium-add") {
      if (
        typeof body.transactionId !== "string" ||
        !/^[A-Za-z0-9-]{6,80}$/.test(body.transactionId.trim())
      ) {
        return jsonResponse(400, { error: "Enter a valid payment reference ID" });
      }
      if (typeof body.showName !== "boolean") {
        return jsonResponse(400, { error: "Choose whether the donor name can be displayed" });
      }
      let displayName: string | null = null;
      if (body.showName) {
        if (typeof body.displayName !== "string") {
          return jsonResponse(400, { error: "Enter the donor name or disable name display" });
        }
        displayName = body.displayName.trim().replace(/\s+/gu, " ");
        if (
          displayName.length < 1 ||
          displayName.length > 40
        ) {
          return jsonResponse(400, { error: "Enter a valid donor name (1–40 characters)" });
        }
      }
      if (
        body.showNamePartially !== undefined &&
        typeof body.showNamePartially !== "boolean"
      ) {
        return jsonResponse(400, { error: "Invalid partial-name setting" });
      }
      const { data, error } = await supabase.rpc("add_premium_reference", {
        reference: body.transactionId.trim().toUpperCase(),
        donor_name: displayName,
        donor_name_partial: body.showNamePartially === true,
      });
      if (error) return jsonResponse(500, { error: "Could not add premium reference" });
      if (data !== true) {
        return jsonResponse(409, { error: "This reference already exists or is already connected" });
      }
      return jsonResponse(200, { status: "added" });
    }

    if (body.action === "admin-premium-remove") {
      if (
        typeof body.transactionId !== "string" ||
        !/^[A-Za-z0-9-]{6,80}$/.test(body.transactionId.trim())
      ) {
        return jsonResponse(400, { error: "Enter a valid payment reference ID" });
      }
      const { data, error } = await supabase
        .from("premium_reference_grants")
        .delete()
        .eq("transaction_id", body.transactionId.trim().toUpperCase())
        .is("claimed_device_hash", null)
        .select("transaction_id")
        .maybeSingle();
      if (error) return jsonResponse(500, { error: "Could not remove premium reference" });
      if (!data) return jsonResponse(409, { error: "Reference is missing or already connected" });
      return jsonResponse(200, { status: "removed" });
    }

    const { data, error } = await supabase.rpc("clear_premium_user_data");
    if (error) return jsonResponse(500, { error: "Could not clear premium user data" });
    return jsonResponse(200, { cleared: data });
  }

  if (body.action === "donors") {
    const { data: manualDonors, error: manualError } = await supabase
      .from("premium_reference_grants")
      .select("transaction_id, display_name, display_name_partial, created_at, claimed_at")
      .not("display_name", "is", null)
      .limit(1000);
    if (manualError) return jsonResponse(500, { error: "Could not load donor names" });
    const { data: requestDonors, error: requestError } = await supabase
      .from("premium_requests")
      .select("transaction_id, display_name, display_name_partial, is_pinned, reviewed_at")
      .eq("status", "approved")
      .not("display_name", "is", null)
      .order("reviewed_at", { ascending: false })
      .limit(1000);
    if (requestError) return jsonResponse(500, { error: "Could not load donor names" });
    const donorsByReference = new Map<string, {
      display_name: string | null;
      display_name_partial: boolean;
      is_pinned: boolean;
      reviewed_at: string;
    }>();
    for (const donor of requestDonors ?? []) {
      if (!donorsByReference.has(donor.transaction_id)) {
        donorsByReference.set(donor.transaction_id, {
          display_name: donor.display_name,
          display_name_partial: donor.display_name_partial,
          is_pinned: donor.is_pinned,
          reviewed_at: donor.reviewed_at,
        });
      }
    }
    for (const donor of manualDonors ?? []) {
      donorsByReference.set(donor.transaction_id, {
        display_name: donor.display_name,
        display_name_partial: donor.display_name_partial,
        is_pinned: false,
        reviewed_at: donor.claimed_at ?? donor.created_at,
      });
    }
    const donors = [...donorsByReference.values()].map((donor) => {
      const name = donor.display_name ?? "";
      const characters = Array.from(name);
      const displayName = donor.display_name_partial
        ? characters.length > 2
          ? `${characters[0]}${"*".repeat(characters.length - 2)}${characters[characters.length - 1]}`
          : `${characters[0] ?? ""}***`
        : name;
      return {
        displayName,
        isPinned: false,
        reviewedAt: donor.reviewed_at,
      };
    });
    donors.sort((a, b) => a.reviewedAt.localeCompare(b.reviewedAt));
    return jsonResponse(200, { donors });
  }

  if (body.action === "admin-create-admin-start") {
    const gateAuthorized = await verifyRegistrationToken(body.registrationToken, serviceRoleKey, browserHash);
    const actor = gateAuthorized
      ? { email: "adityakumar.as12345@gmail.com", role: "owner" }
      : await authorizeAdmin(supabase, body.adminToken, "manage_admins", String(body.action), null, request, browserHash);
    if (!actor || actor.role !== "owner" || actor.email !== "adityakumar.as12345@gmail.com") {
      return jsonResponse(403, { error: "Use the confidential entry credentials or sign in as the mother administrator to create an account." });
    }
    const email = typeof body.targetEmail === "string" ? body.targetEmail.trim().toLowerCase() : "";
    const name = typeof body.adminName === "string" ? body.adminName.trim().replace(/\s+/gu, " ") : "";
    const password = typeof body.newPassword === "string" ? body.newPassword : "";
    const permissions = Array.isArray(body.permissions) ? body.permissions.filter((p): p is string => typeof p === "string") : [];
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) || email.length > 254 ||
        email === "admin@email.com" || email === "adityakumar.as12345@gmail.com" || isDisposableAdminEmail(email)) {
      return jsonResponse(400, { error: "Enter a valid, non-disposable administrator email." });
    }
    if (!name || name.length > 80) return jsonResponse(400, { error: "Enter an administrator name (1–80 characters)." });
    if (password.length < 4 || password.length > 256) return jsonResponse(400, { error: "Use a password between 4 and 256 characters." });
    if (permissions.some((p) => !(ADMIN_PERMISSION_KEYS as readonly string[]).includes(p))) {
      return jsonResponse(400, { error: "One or more permissions are invalid." });
    }
    const { data: existing, error: existingError } = await supabase.rpc("get_premium_admin_profile", { p_email: email });
    if (existingError) return jsonResponse(500, { error: "Could not check whether this administrator already exists." });
    if (existing) return jsonResponse(409, { error: "An administrator with this email already exists." });
    const authClient = createClient(supabaseUrl, publicApiKey, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
    });
    const { error: otpError } = await authClient.auth.signInWithOtp({
      email,
      options: { shouldCreateUser: true },
    });
    if (otpError) return jsonResponse(502, { error: "Could not send the administrator verification code." });
    return jsonResponse(200, { sent: true, email, expiresInSeconds: 1800 });
  }

  if (body.action === "admin-create-admin-verify") {
    const gateAuthorized = await verifyRegistrationToken(body.registrationToken, serviceRoleKey, browserHash);
    const actor = gateAuthorized
      ? { email: "adityakumar.as12345@gmail.com", role: "owner" }
      : await authorizeAdmin(supabase, body.adminToken, "manage_admins", String(body.action), null, request, browserHash);
    if (!actor || actor.role !== "owner" || actor.email !== "adityakumar.as12345@gmail.com") {
      return jsonResponse(403, { error: "Only the mother administrator can create accounts." });
    }
    const email = typeof body.targetEmail === "string" ? body.targetEmail.trim().toLowerCase() : "";
    const code = typeof body.otpCode === "string" ? body.otpCode.trim() : "";
    const name = typeof body.adminName === "string" ? body.adminName.trim().replace(/\s+/gu, " ") : "";
    const password = typeof body.newPassword === "string" ? body.newPassword : "";
    const permissions = Array.isArray(body.permissions) ? body.permissions.filter((p): p is string => typeof p === "string") : [];
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) || !/^\d{4,8}$/.test(code)) {
      return jsonResponse(400, { error: "Enter the administrator email and verification code." });
    }
    if (!name || name.length > 80 || password.length < 4 || password.length > 256) {
      return jsonResponse(400, { error: "Enter valid administrator details." });
    }
    if (permissions.some((p) => !(ADMIN_PERMISSION_KEYS as readonly string[]).includes(p))) {
      return jsonResponse(400, { error: "One or more permissions are invalid." });
    }
    const authClient = createClient(supabaseUrl, publicApiKey, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
    });
    const { error: otpError } = await authClient.auth.verifyOtp({ email, token: code, type: "email" });
    if (otpError) return jsonResponse(401, { error: "Incorrect or expired administrator verification code." });
    const { data: existing, error: existingError } = await supabase.rpc("get_premium_admin_profile", { p_email: email });
    if (existingError) return jsonResponse(500, { error: "Could not check whether this administrator already exists." });
    if (existing) return jsonResponse(409, { error: "An administrator with this email already exists." });
    const { data, error } = await supabase.rpc("manage_premium_admin", {
      p_actor: actor.email, p_action: "create", p_email: email,
      p_permissions: permissions, p_password: password, p_name: name,
    });
    if (error) return jsonResponse(400, { error: error.message });
    return jsonResponse(200, { created: true, admin: data });
  }

  if (body.action === "admin-sessions-list") {
    const actor = await authorizeAdmin(supabase, body.adminToken, undefined, String(body.action), null, request, browserHash);
    if (!actor) return jsonResponse(401, { error: "Sign in again to view your active instances." });
    const targetEmail = typeof body.targetEmail === "string" ? body.targetEmail.trim().toLowerCase() : actor.email;
    if (targetEmail !== actor.email && actor.role !== "owner") return jsonResponse(403, { error: "You can only view your own signed-in instances." });
    const { data, error } = await supabase.rpc("list_premium_admin_sessions", { p_email: targetEmail });
    if (error || !Array.isArray(data)) return jsonResponse(500, { error: "Could not load active instances." });
    const currentHash = await sha256Hex(String(body.adminToken));
    const sessions = data.map((item: Record<string, unknown>) => {
      const { sessionHash, ...safe } = item;
      return { ...safe, isCurrent: sessionHash === currentHash };
    });
    return jsonResponse(200, { sessions, targetEmail });
  }

  if (["admin-session-revoke","admin-sessions-revoke-others","admin-sessions-revoke-all"].includes(String(body.action))) {
    const actor = await authorizeAdmin(supabase, body.adminToken, undefined, String(body.action), null, request, browserHash);
    if (!actor) return jsonResponse(401, { error: "Sign in again to manage signed-in instances." });
    const targetEmail = typeof body.targetEmail === "string" ? body.targetEmail.trim().toLowerCase() : actor.email;
    if (targetEmail !== actor.email && actor.role !== "owner") return jsonResponse(403, { error: "You can only terminate your own signed-in instances." });
    let result: unknown;
    let error: { message: string } | null = null;
    if (body.action === "admin-session-revoke") {
      const sessionId = typeof body.sessionId === "string" ? body.sessionId : "";
      if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(sessionId)) return jsonResponse(400, { error: "Invalid session identifier." });
      const response = await supabase.rpc("revoke_premium_admin_session_by_id", { p_email: targetEmail, p_session_id: sessionId });
      result = response.data; error = response.error;
      if (!error && result !== true) return jsonResponse(404, { error: "That instance was not found or was already signed out." });
    } else {
      const keepHash = body.action === "admin-sessions-revoke-others" && targetEmail === actor.email
        ? await sha256Hex(String(body.adminToken)) : null;
      const response = await supabase.rpc("revoke_premium_admin_sessions_except", { p_email: targetEmail, p_keep_session_hash: keepHash });
      result = response.data; error = response.error;
    }
    if (error) return jsonResponse(500, { error: "Could not terminate the selected administrator sessions." });
    return jsonResponse(200, { revoked: body.action === "admin-session-revoke" ? 1 : Number(result) || 0 });
  }

  if (body.action === "admin-login-start") {
    const email = typeof body.adminEmail === "string" ? body.adminEmail.trim().toLowerCase() : "";
    const password = typeof body.adminPassword === "string" ? body.adminPassword : "";
    const setupMode = body.setupMode === true;
    const emailParts = email.split("@");
    const emailValid = email.length <= 254 && emailParts.length === 2 &&
      emailParts[0].length > 0 && emailParts[1].includes(".") &&
      !emailParts[1].startsWith(".") && !emailParts[1].endsWith(".") &&
      !email.includes(" ") && !email.includes("\t") && !email.includes("\n");
    if (!emailValid) return jsonResponse(400, { error: "Enter a valid admin Gmail address." });
    if (!browserHash) return jsonResponse(400, { error: "This browser could not create a sign-in ID. Refresh the page and try again." });
    if (email === "admin@email.com") {
      return jsonResponse(403, { error: "Use the setup credentials to open the account creation page." });
    }
    if (motherLoginAliases.has(email) && password === "2010") {
      const session = await createAdminSession(supabase, motherAccountEmail, browserHash, request, true);
      if (!session) return jsonResponse(403, { error: "The mother administrator account is unavailable." });
      await supabase.rpc("record_premium_admin_audit", {
        p_actor: motherAccountEmail, p_action: "login-password", p_target: null, p_ip: getClientIp(request),
      });
      return jsonResponse(200, { authorized: true, token: session.token, profile: session.profile });
    }
    const { data: state, error: stateError } = await supabase.rpc("get_premium_admin_password_state", { p_email: email });
    if (stateError || !state) return jsonResponse(401, { error: "Invalid admin email or password." });
    const isMaster = motherLoginAliases.has(email);
    if (isMaster && state.passwordInitialized === false) {
      if (!setupMode) return jsonResponse(403, { error: "Use the setup credentials to create the first master account." });
      const name = typeof body.adminName === "string" ? body.adminName.trim().replace(/\\s+/gu, " ") : "";
      if (!name || name.length > 80) return jsonResponse(400, { error: "Enter the master administrator name." });
      if (password.length < 4 || password.length > 256) return jsonResponse(400, { error: "Choose a password between 4 and 256 characters." });
      const authClient = createClient(supabaseUrl, publicApiKey, { auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false } });
      const { error: otpError } = await authClient.auth.signInWithOtp({ email, options: { shouldCreateUser: true } });
      if (otpError) return jsonResponse(502, { error: "Master email verification could not be sent." });
      return jsonResponse(200, { sent: true, email, expiresInSeconds: 1800, needsInitialPassword: true, name });
    }
    if (isMaster && setupMode) return jsonResponse(409, { error: "The master account already exists. Sign in from the main page." });
    const { data: passwordValid, error: passwordError } = await supabase.rpc("verify_premium_admin_password", { candidate_email: email, candidate_password: password });
    if (passwordError || passwordValid !== true) return jsonResponse(401, { error: "Invalid admin email or password." });
    const session = await createAdminSession(supabase, email, browserHash, request, true);
    if (!session) return jsonResponse(403, { error: "Admin account is disabled." });
    await supabase.rpc("record_premium_admin_audit", { p_actor: email, p_action: "login-password", p_target: null, p_ip: getClientIp(request) });
    return jsonResponse(200, { authorized: true, token: session.token, profile: session.profile });
  }
  if (body.action === "admin-login-verify") {
    return jsonResponse(410, { error: "Master Gmail OTP setup is disabled." });
  }
  if (body.action === "admin-session") {
    const profile = await authorizeAdmin(supabase, body.adminToken, undefined, "session-resumed", null, request, browserHash);
    if (!profile) return jsonResponse(401, { error: "This saved sign-in expired. Enter your password to continue." });
    return jsonResponse(200, { authorized: true, profile });
  }

  if (body.action === "admin-logout") {
    const profile = await authorizeAdmin(supabase, body.adminToken, undefined, "sign-out", null, request, browserHash);
    if (!profile || typeof body.adminToken !== "string") return jsonResponse(401, { error: "Admin session already expired" });
    const { error } = await supabase.rpc("revoke_premium_admin_session", { p_session_hash: await sha256Hex(body.adminToken) });
    if (error) return jsonResponse(500, { error: "Could not end the admin session" });
    return jsonResponse(200, { signedOut: true });
  }

  if (body.action === "admin-profile-update") {
    const profile = await authorizeAdmin(supabase, body.adminToken, undefined, "profile-update", null, request, browserHash);
    if (!profile) return jsonResponse(403, { error: "Sign in again to update this profile" });
    const currentPassword = typeof body.currentPassword === "string" ? body.currentPassword : "";
    const newEmail = typeof body.newEmail === "string" ? body.newEmail : "";
    const newPassword = typeof body.newPassword === "string" ? body.newPassword : "";
    if ((newEmail || newPassword) && !await authorizeAdminPassword(supabase, String(profile.email), currentPassword)) {
      return jsonResponse(403, { error: "Enter the current password to change email or password" });
    }
    const { data, error } = await supabase.rpc("update_premium_admin_credentials", {
      p_email: profile.email,
      p_password: currentPassword,
      p_new_email: newEmail,
      p_new_password: newPassword,
      p_name: typeof body.adminName === "string" ? body.adminName : "",
    });
    if (error) return jsonResponse(400, { error: error.message });
    return jsonResponse(200, { profile: data, passwordChanged: Boolean(newPassword) });
  }

  if (["admin-list-admins", "admin-create-admin", "admin-set-admin-permissions", "admin-reset-admin-password", "admin-remove-admin", "admin-account-enable", "admin-account-disable", "admin-audit-list"].includes(String(body.action))) {
    const targetEmail = typeof body.targetEmail === "string" ? body.targetEmail.trim().toLowerCase() : null;
    const actor = await authorizeAdmin(
      supabase, body.adminToken, "manage_admins", String(body.action), targetEmail, request, browserHash,
    );
    if (!actor || actor.role !== "owner") return jsonResponse(403, { error: "Only the master administrator can manage admin accounts" });
    if (body.action === "admin-create-admin") return jsonResponse(428, { error: "Use the email verification workflow to create an administrator." });
    if (body.action === "admin-audit-list") {
      const { data, error } = await supabase.rpc("get_premium_admin_audit", { p_limit: 300 });
      if (error) return jsonResponse(500, { error: "Could not load admin audit history" });
      return jsonResponse(200, { audit: data ?? [] });
    }
    const action = body.action === "admin-list-admins" ? "list"
      : body.action === "admin-set-admin-permissions" ? "permissions"
      : body.action === "admin-reset-admin-password" ? "reset-password"
      : body.action === "admin-account-enable" ? "enable"
      : body.action === "admin-account-disable" ? "disable" : "remove";
    const { data, error } = await supabase.rpc("manage_premium_admin", {
      p_actor: actor.email,
      p_action: action,
      p_email: targetEmail,
      p_permissions: Array.isArray(body.permissions) ? body.permissions : [],
      p_password: typeof body.newPassword === "string" ? body.newPassword : null,
      p_name: typeof body.adminName === "string" ? body.adminName : null,
    });
    if (error) return jsonResponse(400, { error: error.message });
    return jsonResponse(200, { admins: action === "list" ? data : undefined, result: action === "list" ? undefined : data });
  }

  if (body.action === "supported-devices-get") {
    const { data, error } = await supabase.rpc("get_app_supported_device_catalog");
    if (error || !Array.isArray(data)) return jsonResponse(500, { error: "Could not load supported device catalog" });
    return jsonResponse(200, { categories: data });
  }

  if (body.action === "admin-supported-devices-get" || body.action === "admin-supported-devices-set") {
    const permission = await authorizeAdmin(
      supabase, body.adminToken, "manage_supported_devices", String(body.action), null, request, browserHash,
    );
    if (!permission) return jsonResponse(403, { error: "Settings permission required" });
    if (body.action === "admin-supported-devices-get") {
      const { data, error } = await supabase.rpc("get_app_supported_device_catalog");
      if (error || !Array.isArray(data)) return jsonResponse(500, { error: "Could not load supported device catalog" });
      return jsonResponse(200, { categories: data });
    }
    if (!Array.isArray(body.categories) || body.categories.length > 40) {
      return jsonResponse(400, { error: "Catalog supports up to 40 categories" });
    }
    let normalized: { name: string; items: { name: string; status: string }[] }[];
    try {
      normalized = body.categories.map((category) => {
        if (!category || typeof category !== "object") throw new Error("Invalid category");
        const item = category as Record<string, unknown>;
        if (typeof item.name !== "string" || !Array.isArray(item.items)) throw new Error("Each category needs a name and items");
        return {
          name: item.name.trim().slice(0, 80),
          items: item.items.slice(0, 200).map((entry) => {
            if (!entry || typeof entry !== "object") throw new Error("Invalid supported device entry");
            const child = entry as Record<string, unknown>;
            const status = child.status === "Unsupported" || child.status === "Not tested" ? child.status : "Supported";
            return { name: typeof child.name === "string" ? child.name.trim().slice(0, 100) : "", status };
          }),
        };
      });
    } catch (error) {
      return jsonResponse(400, { error: error instanceof Error ? error.message : "Invalid supported-device catalog" });
    }
    if (normalized.some((category) => !category.name || category.items.some((item) => !item.name))) {
      return jsonResponse(400, { error: "Category and device names cannot be blank" });
    }
    const { data, error } = await supabase.rpc("set_app_supported_device_catalog", { p_categories: normalized });
    if (error || !Array.isArray(data)) return jsonResponse(400, { error: error?.message || "Could not save supported device catalog" });
    return jsonResponse(200, { categories: data });
  }

  if (body.action === "admin-analytics-period-devices") {
    if (!await authorizeAdmin(supabase, body.adminToken, permissionForAction(String(body.action)) || "view_analytics", String(body.action), null, request, browserHash)) {
      return jsonResponse(403, { error: "The required analytics permission is missing." });
    }
    const period = body.period === "weekly" ? "weekly" : body.period === "daily" ? "daily" : "";
    const start = typeof body.periodStart === "string" && /^\d{4}-\d{2}-\d{2}$/.test(body.periodStart)
      ? body.periodStart : "";
    if (!period || !start) return jsonResponse(400, { error: "Choose a valid analytics period" });
    const { data, error } = await supabase.rpc("get_admin_usage_period_devices", {
      p_period: period, p_start: start,
    });
    if (error || !Array.isArray(data)) return jsonResponse(400, { error: error?.message || "Could not load devices for this period" });
    return jsonResponse(200, { period, periodStart: start, devices: data });
  }

  if (body.action === "admin-device-inventory") {
    if (!await authorizeAdmin(supabase, body.adminToken, "view_devices", String(body.action), null, request, browserHash)) {
      return jsonResponse(403, { error: "Device inventory permission required." });
    }
    const { data, error } = await supabase.rpc("get_admin_device_inventory");
    if (error || !Array.isArray(data)) {
      return jsonResponse(500, { error: error?.message || "Could not load registered devices" });
    }
    return jsonResponse(200, { devices: data });
  }

  if (body.action === "admin-analytics") {
    if (!await authorizeAdmin(supabase, body.adminToken, "view_analytics", String(body.action), null, request, browserHash)) {
      return jsonResponse(403, { error: "Analytics permission required" });
    }
    const [
      { data, error },
      { data: devices, error: deviceError },
      { data: versionUsage, error: versionUsageError },
    ] = await Promise.all([
      supabase.rpc("get_app_usage_analytics", { p_days: 7 }),
      supabase.rpc("get_admin_device_inventory"),
      supabase.rpc("get_app_version_analytics"),
    ]);
    if (error || !data || deviceError || !Array.isArray(devices) || versionUsageError || !versionUsage) {
      return jsonResponse(500, { error: "Could not load app analytics" });
    }
    data.devices = devices;
    data.versionUsage = versionUsage;
    return jsonResponse(200, { analytics: data });
  }

  if (body.action === "admin-list") {
    if (!await authorizeAdmin(supabase, body.adminToken, "review_payments", String(body.action), null, request, browserHash)) {
      return jsonResponse(403, { error: "Payment review permission required" });
    }
    const { data, error } = await supabase
      .from("premium_requests")
      .select("id, transaction_id, submitted_at, status, display_name, display_name_partial, device_hash")
      .eq("status", "pending")
      .order("submitted_at", { ascending: true })
      .limit(100);
    if (error) return jsonResponse(500, { error: "Could not load pending requests" });
    const requests = data ?? [];
    const { data: profiles } = await supabase.rpc("get_admin_device_profiles", {
      p_device_hashes: requests.map((item) => item.device_hash),
    });
    return jsonResponse(200, { requests: requests.map((item) => ({
      ...item,
      profile_email: typeof profiles?.[item.device_hash]?.email === "string"
        ? profiles[item.device_hash].email
        : null,
    })) });
  }

  if (
    body.action === "admin-list-active" ||
    body.action === "admin-revoke" ||
    body.action === "admin-unlink-reference" ||
    body.action === "admin-approve-reference" ||
    body.action === "admin-approve" ||
    body.action === "admin-reject"
  ) {
    const permission = permissionForAction(String(body.action)) || "manage_active_premium";
    if (!await authorizeAdmin(supabase, body.adminToken, permission, String(body.action), body.transactionId, request, browserHash)) {
      return jsonResponse(403, { error: `${permission} permission required` });
    }

    if (body.action === "admin-list-active") {
      const { data, error } = await supabase
        .from("premium_entitlements")
        .select("transaction_id, granted_at, device_hash")
        .order("granted_at", { ascending: false })
        .limit(500);
      if (error) return jsonResponse(500, { error: "Could not load active entitlements" });
      const entitlements = data ?? [];
      if (entitlements.length === 0) {
        return jsonResponse(200, { entitlements });
      }

      const references = entitlements.map((item) => item.transaction_id);
      const [{ data: requests, error: requestsError }, { data: grants, error: grantsError }] =
        await Promise.all([
          supabase
            .from("premium_requests")
            .select("transaction_id, device_hash, status, display_name, display_name_partial")
            .in("transaction_id", references),
          supabase
            .from("premium_reference_grants")
            .select("transaction_id, display_name, display_name_partial, claimed_device_hash")
            .in("transaction_id", references),
        ]);
      if (requestsError || grantsError) {
        return jsonResponse(500, { error: "Could not load active donor names" });
      }

      const namesByReference = new Map((requests ?? []).map((item) => [
        item.transaction_id,
        { display_name: item.display_name, display_name_partial: item.display_name_partial },
      ]));
      const requestsByReference = new Map((requests ?? []).map((item) => [item.transaction_id, item]));
      const grantsByReference = new Map((grants ?? []).map((item) => [item.transaction_id, item]));
      for (const grant of grants ?? []) {
        namesByReference.set(grant.transaction_id, {
          display_name: grant.display_name ?? namesByReference.get(grant.transaction_id)?.display_name ?? null,
          display_name_partial: grant.display_name_partial || namesByReference.get(grant.transaction_id)?.display_name_partial || false,
        });
      }
      const { data: emailGrantData, error: emailGrantError } = await supabase.rpc("list_app_email_premium_grants");
      if (emailGrantError || !Array.isArray(emailGrantData)) {
        return jsonResponse(500, { error: "Could not load email premium grants" });
      }
      const emailGrants = new Map(emailGrantData.map((item: Record<string, unknown>) => [
        String(item.transactionId), item,
      ]));
      const { data: profileData, error: profileError } = await supabase.rpc("get_admin_device_profiles", {
        p_device_hashes: entitlements.map((item) => item.device_hash),
      });
      if (profileError || !profileData) return jsonResponse(500, { error: "Could not load linked device profiles" });
      for (const [reference, emailGrant] of emailGrants) {
        namesByReference.set(reference, {
          display_name: typeof emailGrant.displayName === "string" ? emailGrant.displayName : null,
          display_name_partial: false,
        });
      }
      return jsonResponse(200, {
        entitlements: entitlements.map((item) => {
          const { device_hash: deviceHash, ...publicEntitlement } = item;
          const emailGrant = emailGrants.get(item.transaction_id);
          return {
            ...publicEntitlement,
            ...namesByReference.get(item.transaction_id),
            email: typeof emailGrant?.email === "string" ? emailGrant.email : null,
            profileEmail: typeof profileData[deviceHash]?.email === "string" ? profileData[deviceHash].email : null,
            isEmailGrant: Boolean(emailGrant),
            canClearDeviceRegistration:
              grantsByReference.get(item.transaction_id)?.claimed_device_hash === deviceHash ||
              (requestsByReference.get(item.transaction_id)?.status === "approved" &&
                requestsByReference.get(item.transaction_id)?.device_hash === deviceHash),
          };
        }),
      });
    }

    if (body.action === "admin-unlink-reference") {
      if (
        typeof body.transactionId !== "string" ||
        !/^[A-Za-z0-9-]{6,80}$/.test(body.transactionId.trim())
      ) {
        return jsonResponse(400, { error: "Enter a valid payment reference ID" });
      }
      const { data, error } = await supabase.rpc("admin_unlink_premium_reference", {
        p_transaction_id: body.transactionId.trim().toUpperCase(),
      });
      if (error) return jsonResponse(500, { error: "Could not clear the reference registration" });
      if (data !== true) {
        return jsonResponse(409, { error: "This reference is not currently registered to a device" });
      }
      return jsonResponse(200, { status: "unlinked" });
    }

    if (body.action === "admin-revoke") {
      if (
        typeof body.transactionId !== "string" ||
        !/^[A-Za-z0-9-]{6,80}$/.test(body.transactionId.trim())
      ) {
        return jsonResponse(400, { error: "Enter a valid payment reference ID" });
      }
      const transactionId = body.transactionId.trim().toUpperCase();
      if (transactionId.startsWith("EMAIL-")) {
        const { data, error } = await supabase.rpc("revoke_app_email_premium_grant", {
          p_transaction_id: transactionId,
        });
        if (error) return jsonResponse(500, { error: "Could not revoke email-based premium access" });
        if (data !== true) return jsonResponse(404, { error: "No active email grant matches this account" });
        return jsonResponse(200, { status: "revoked" });
      }
      const { data, error } = await supabase
        .from("premium_entitlements")
        .delete()
        .eq("transaction_id", transactionId)
        .select("transaction_id")
        .maybeSingle();
      if (error) return jsonResponse(500, { error: "Could not revoke premium access" });
      if (!data) return jsonResponse(404, { error: "No active premium matches this reference" });
      return jsonResponse(200, { status: "revoked" });
    }

    let requestId: string;
    if (body.action === "admin-approve-reference") {
      if (
        typeof body.transactionId !== "string" ||
        !/^[A-Za-z0-9-]{6,80}$/.test(body.transactionId.trim())
      ) {
        return jsonResponse(400, { error: "Enter a valid payment reference ID" });
      }
      const transactionId = body.transactionId.trim().toUpperCase();
      const { data, error } = await supabase
        .from("premium_requests")
        .select("id")
        .eq("transaction_id", transactionId)
        .eq("status", "pending")
        .maybeSingle();
      if (error) return jsonResponse(500, { error: "Could not find this pending reference" });
      if (!data) return jsonResponse(404, { error: "No pending request matches this reference" });
      requestId = data.id;
    } else {
      if (
        typeof body.requestId !== "string" ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
          .test(body.requestId)
      ) {
        return jsonResponse(400, { error: "Invalid request ID" });
      }
      requestId = body.requestId;
    }

    if (body.action === "admin-approve" || body.action === "admin-approve-reference") {
      const { data: approved, error } = await supabase.rpc(
        "approve_premium_request",
        { request_id: requestId },
      );
      if (error) return jsonResponse(500, { error: "Could not approve request" });
      if (approved !== true) {
        return jsonResponse(409, { error: "Request was already handled or conflicts with an existing entitlement" });
      }
      return jsonResponse(200, { status: "approved" });
    }

    const { data, error } = await supabase
      .from("premium_requests")
      .update({ status: "rejected", reviewed_at: new Date().toISOString() })
      .eq("id", requestId)
      .eq("status", "pending")
      .select("id")
      .maybeSingle();
    if (error) return jsonResponse(500, { error: "Could not reject request" });
    if (!data) return jsonResponse(409, { error: "Request was already handled" });
      return jsonResponse(200, { status: "rejected" });
  }

  if (body.action === "app-access") {
    if (
      typeof body.androidId !== "string" ||
      body.androidId.trim().length < 8 ||
      body.androidId.trim().length > 128
    ) {
      return jsonResponse(400, { error: "Invalid device identifier" });
    }

    const hasTelemetry =
      typeof body.deviceModel === "string" &&
      typeof body.cpu === "string" &&
      Number.isSafeInteger(body.ramMb) &&
      Number.isSafeInteger(body.storageMb) &&
      body.deviceModel.trim().length >= 1 &&
      body.deviceModel.trim().length <= 120 &&
      body.cpu.trim().length >= 1 &&
      body.cpu.trim().length <= 120;
    const { data, error } = await supabase.rpc("check_app_startup", {
      p_android_id: body.androidId.trim(),
      p_model: hasTelemetry ? body.deviceModel : null,
      p_ram_mb: hasTelemetry ? body.ramMb : null,
      p_storage_mb: hasTelemetry ? body.storageMb : null,
      p_cpu: hasTelemetry ? body.cpu : null,
      p_ip_address: getClientIp(request),
      p_version_code: Number.isSafeInteger(body.versionCode) ? body.versionCode : 0,
    });
    if (error || !data || typeof data.accessAllowed !== "boolean") {
      return jsonResponse(500, { error: "Could not verify app access" });
    }
    const { data: settings } = await supabase.rpc("get_premium_public_settings");
    const appSettings = settings && typeof settings === "object" ? settings as Record<string, unknown> : {};
    const appRemoteConfig = appSettings.remoteConfig && typeof appSettings.remoteConfig === "object"
      ? appSettings.remoteConfig as Record<string, unknown>
      : {};
    const packageRelease = getPackageRelease(appRemoteConfig, appPackageId);
    const legacyRelease = appPackageId === "com.antutu.ABenchMark" && packageRelease === null;
    const selectedRelease = packageRelease ?? (legacyRelease ? appSettings : null);
    const selectedPath = typeof selectedRelease?.apkStoragePath === "string"
      ? selectedRelease.apkStoragePath
      : "";
    const releaseMinimum = Number.isSafeInteger(selectedRelease?.minimumVersionCode)
      ? Number(selectedRelease.minimumVersionCode)
      : data.minimumVersionCode;
    const releaseLatest = Number.isSafeInteger(selectedRelease?.latestVersionCode)
      ? Number(selectedRelease.latestVersionCode)
      : data.latestVersionCode;
    const minimumVersionRequired = appRemoteConfig.enforceMinimumVersion === true
      ? releaseLatest
      : releaseMinimum;
    const result = {
      ...data,
      minimumVersionCode: minimumVersionRequired,
      latestVersionCode: releaseLatest,
      accessCheckIntervalHours: data.premium === true
        ? appSettings.premiumAccessCheckIntervalHours ?? 6
        : appSettings.normalAccessCheckIntervalHours ?? 6,
      displayVersion: selectedRelease?.displayVersion ?? "",
      releaseNotes: selectedRelease?.releaseNotes ?? "",
      apkSha256: selectedRelease?.apkSha256 ?? "",
      apkStoragePath: selectedPath,
      updateUrl: "",
      updateFallbackUrl: typeof selectedRelease?.updateFallbackUrl === "string" &&
          selectedRelease.updateFallbackUrl
        ? selectedRelease.updateFallbackUrl
        : typeof appRemoteConfig.updateFallbackUrl === "string"
          ? appRemoteConfig.updateFallbackUrl
          : "",
      updateMessage: selectedRelease?.updateMessage ?? appSettings.updateMessage ?? "A new update is available.",
      remoteConfig: appRemoteConfig,
      popups: Array.isArray(appSettings.popups) ? appSettings.popups : [],
    };
    if (typeof result.apkStoragePath === "string" && result.apkStoragePath) {
      result.updateUrl = updateDownloadEndpoint(supabaseUrl, false, appPackageId);
    }
    return jsonResponse(200, result);
  }

  if (typeof body.androidId !== "string" || body.androidId.length < 8 || body.androidId.length > 128) {
    return jsonResponse(400, { error: "Invalid device identifier" });
  }

  const { data: deviceHash, error: hashError } = await supabase.rpc(
    "hash_premium_device_id",
    { android_id: body.androidId },
  );
  if (hashError || typeof deviceHash !== "string") {
    return jsonResponse(500, { error: "Could not securely identify this device" });
  }

  if (body.action === "app-feedback-get") {
    const { data, error } = await supabase.rpc("get_app_feedback_vote", { p_device_hash: deviceHash });
    if (error || !data) return jsonResponse(500, { error: "Could not load device feedback" });
    return jsonResponse(200, data as Record<string, unknown>);
  }

  if (body.action === "app-feedback-set") {
    if (typeof body.works !== "boolean") {
      return jsonResponse(400, { error: "Feedback must be yes or no" });
    }
    const { data, error } = await supabase.rpc("set_app_feedback_vote", {
      p_device_hash: deviceHash,
      p_works: body.works,
    });
    if (error || !data) return jsonResponse(500, { error: "Could not save device feedback" });
    return jsonResponse(200, data as Record<string, unknown>);
  }

  if (body.action === "app-email-otp-send") {
    const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
    if (email.length > 254 || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
      return jsonResponse(400, { error: "Enter a valid email address" });
    }
    const emailProvider = (Deno.env.get("APP_EMAIL_PROVIDER") || "supabase_auth").trim().toLowerCase();
    const useSupabaseAuth = emailProvider === "supabase_auth" || emailProvider === "gmail";
    if (!useSupabaseAuth && emailProvider !== "brevo") {
      return jsonResponse(503, { error: "Unsupported email provider configuration" });
    }
    const brevoApiKey = Deno.env.get("BREVO_API_KEY");
    const senderEmail = Deno.env.get("BREVO_SENDER_EMAIL");
    if (!useSupabaseAuth && (!brevoApiKey || !senderEmail)) {
      return jsonResponse(503, { error: "Email verification is not configured yet" });
    }
    const code = useSupabaseAuth
      ? crypto.randomUUID()
      : String(crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000).padStart(6, "0");
    const encoder = new TextEncoder();
    const secretMaterial = encoder.encode(`${serviceRoleKey}\u0000${deviceHash}\u0000${email}\u0000${code}`);
    const codeHash = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", secretMaterial)))
      .map((byte) => byte.toString(16).padStart(2, "0")).join("");
    const { data: allowed, error: rateError } = await supabase.rpc("begin_app_email_otp", {
      p_android_id: body.androidId,
      p_email: email,
      p_code_hash: codeHash,
    });
    if (rateError) return jsonResponse(500, { error: "Could not prepare email verification" });
    if (allowed !== true) {
      return jsonResponse(429, { error: "The email verification request limit was reached. Please wait before trying again." });
    }
    try {
      if (useSupabaseAuth) {
        const authClient = createClient(supabaseUrl, publicApiKey, {
          auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
        });
        const { error } = await authClient.auth.signInWithOtp({
          email,
          options: { shouldCreateUser: true },
        });
        if (error) return jsonResponse(502, { error: "Gmail OTP delivery failed. Check Supabase Auth SMTP settings." });
      } else {
        const mailResponse = await fetch("https://api.brevo.com/v3/smtp/email", {
          method: "POST",
          headers: { "api-key": brevoApiKey!, "content-type": "application/json", accept: "application/json" },
          body: JSON.stringify({
            sender: { email: senderEmail!, name: Deno.env.get("BREVO_SENDER_NAME") || "QSpoof" },
            to: [{ email }],
            subject: "Your QSpoof verification code",
            textContent: `Your QSpoof verification code is ${code}. It expires in 30 minutes. If you did not request this code, ignore this email.`,
            htmlContent: `<p>Your QSpoof verification code is <strong>${code}</strong>.</p><p>It expires in 30 minutes. If you did not request this code, ignore this email.</p>`,
          }),
        });
        if (!mailResponse.ok) return jsonResponse(502, { error: "Brevo could not send the verification email" });
      }
    } catch {
      return jsonResponse(502, { error: "Could not reach the email delivery service" });
    }
    return jsonResponse(200, { sent: true, expiresInSeconds: 1800 });
  }

  if (body.action === "app-email-otp-verify") {
    const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
    const code = typeof body.otpCode === "string" ? body.otpCode.trim() : "";
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) || !/^\d{4,8}$/.test(code)) {
      return jsonResponse(400, { error: "Enter the email and the verification code from your message" });
    }
    const emailProvider = (Deno.env.get("APP_EMAIL_PROVIDER") || "supabase_auth").trim().toLowerCase();
    let data: Record<string, unknown> | null;
    let error: { message: string } | null;
    if (emailProvider === "supabase_auth" || emailProvider === "gmail") {
      const authClient = createClient(supabaseUrl, publicApiKey, {
        auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
      });
      const { error: authError } = await authClient.auth.verifyOtp({ email, token: code, type: "email" });
      if (authError) return jsonResponse(400, { error: "Incorrect or expired verification code." });
      const result = await supabase.rpc("complete_app_email_otp", {
        p_android_id: body.androidId,
        p_email: email,
      });
      data = result.data;
      error = result.error;
    } else if (emailProvider === "brevo") {
      const encoder = new TextEncoder();
      const secretMaterial = encoder.encode(`${serviceRoleKey}\u0000${deviceHash}\u0000${email}\u0000${code}`);
      const codeHash = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", secretMaterial)))
        .map((byte) => byte.toString(16).padStart(2, "0")).join("");
      const result = await supabase.rpc("verify_app_email_otp", {
        p_android_id: body.androidId,
        p_email: email,
        p_code_hash: codeHash,
      });
      data = result.data;
      error = result.error;
    } else {
      return jsonResponse(503, { error: "Unsupported email provider configuration" });
    }
    if (error || !data) return jsonResponse(500, { error: "Could not verify this email" });
    if (data.verified !== true) return jsonResponse(400, { error: data.error || "Email verification failed" });
    return jsonResponse(200, { verified: true, email: data.email });
  }

  if (body.action === "app-device-profile-get") {
    const { data, error } = await supabase.rpc("get_app_device_profile", { p_android_id: body.androidId });
    if (error || !data) return jsonResponse(500, { error: "Could not load this device profile" });
    return jsonResponse(200, { profile: data });
  }

  if (body.action === "app-device-profile-set") {
    if (typeof body.name !== "string" || typeof body.email !== "string") {
      return jsonResponse(400, { error: "Enter a name and email address" });
    }
    const { data, error } = await supabase.rpc("set_app_device_profile", {
      p_android_id: body.androidId, p_display_name: body.name, p_email: body.email,
    });
    if (error || !data) return jsonResponse(400, { error: error?.message || "Could not save profile" });
    if (data.saved !== true) return jsonResponse(429, { error: data.error || "Profile changes are temporarily locked", lockedUntil: data.lockedUntil });
    return jsonResponse(200, { profile: data });
  }

  if (body.action === "device-usage") {
    if (
      typeof body.deviceModel !== "string" ||
      typeof body.cpu !== "string" ||
      !Number.isSafeInteger(body.ramMb) ||
      !Number.isSafeInteger(body.storageMb) ||
      body.deviceModel.trim().length < 1 ||
      body.deviceModel.trim().length > 120 ||
      body.cpu.trim().length < 1 ||
      body.cpu.trim().length > 120
    ) {
      return jsonResponse(400, { error: "Invalid device telemetry" });
    }
    const { data, error } = await supabase.rpc("record_app_device_usage", {
      p_device_hash: deviceHash,
      p_model: body.deviceModel,
      p_ram_mb: body.ramMb,
      p_storage_mb: body.storageMb,
      p_cpu: body.cpu,
    });
    if (error) return jsonResponse(500, { error: "Could not record device usage" });
    if (data !== true) return jsonResponse(400, { error: "Invalid device telemetry" });
    return jsonResponse(200, { recorded: true });
  }

  if (body.action === "status") {
    const { data, error } = await supabase
      .from("premium_entitlements")
      .select("granted_at")
      .eq("device_hash", deviceHash)
      .maybeSingle();
    if (error) return jsonResponse(500, { error: "Could not check entitlement" });
    return jsonResponse(200, { premium: data !== null });
  }

  if (body.action === "self-delink") {
    const { data: entitlement, error: entitlementError } = await supabase
      .from("premium_entitlements")
      .select("transaction_id")
      .eq("device_hash", deviceHash)
      .maybeSingle();
    if (entitlementError) {
      return jsonResponse(500, { error: "Could not check premium on this device" });
    }
    if (!entitlement) return jsonResponse(200, { delinked: false });
    const emailGrant = entitlement.transaction_id.startsWith("EMAIL-");
    const { data, error } = emailGrant
      ? await supabase.rpc("delink_app_email_premium_grant", {
        p_device_hash: deviceHash,
        p_transaction_id: entitlement.transaction_id,
      })
      : await supabase.rpc("delink_premium_device", {
        p_device_hash: deviceHash,
        p_transaction_id: entitlement.transaction_id,
      });
    if (error || typeof data !== "boolean") {
      return jsonResponse(500, { error: "Could not turn off premium on this device" });
    }
    return jsonResponse(200, { delinked: data });
  }

  if (body.action !== "submit") {
    return jsonResponse(400, { error: "This premium service does not support the requested app action. Deploy the current premium Edge Function and migrations." });
  }

  if (
    typeof body.transactionId !== "string" ||
    !/^[A-Za-z0-9-]{6,80}$/.test(body.transactionId.trim())
  ) {
    return jsonResponse(400, { error: "Enter a valid payment reference ID" });
  }

  let displayName: string | null = null;
  if (body.displayName !== undefined && body.displayName !== null) {
    if (typeof body.displayName !== "string") {
      return jsonResponse(400, { error: "Display name must be text" });
    }
    const normalizedName = body.displayName.trim().replace(/\s+/gu, " ");
    const foldedName = normalizedName.normalize("NFKD").toLocaleLowerCase("en")
      .replace(/[\u0300-\u036f]/gu, "").replace(/[^\p{L}\p{N}]+/gu, " ").trim();
    const compactName = foldedName.replace(/\s+/gu, "");
    const blockedTerms = [
      "fuck", "fucker", "fucking", "motherfucker", "shit", "bullshit", "bitch", "bastard",
      "asshole", "arsehole", "dick", "dickhead", "cock", "cunt", "pussy", "whore", "slut",
      "retard", "nigger", "faggot", "twat", "wanker", "prick", "piss off", "son of a bitch",
      "madarchod", "m@darchod", "madarch0d", "behenchod", "bhenchod", "bhench0d", "bhosdike",
      "bhosdi", "bhosda", "chutiya", "chutiye", "chut", "gaand", "gandu", "ganduwe",
      "lodu", "lawde", "laude", "lund", "lulli", "randi", "harami", "haraami", "kamina",
      "kaminey", "kutta", "kutti", "suar", "saala", "saali", "teri maa", "teri behen",
      "maa ki", "behen ki", "gaand mara", "jhaatu", "jhatu", "bakchod", "bakchodi", "chodu",
      "choot", "chootiya", "tharki", "nalla", "gadha", "haramkhor",
      "मादरचोद", "मादरचोद", "बहनचोद", "भेनचोद", "भोसड़ी", "भोसड़ीके", "चूतिया", "चूत",
      "गांड", "गांडू", "लंड", "लौड़ा", "लौड़े", "रंडी", "हरामी", "कमीना", "कमीनी", "साला",
      "साली", "झाटू", "बकचोद", "चोद", "चोदना", "हरामखोर", "कुत्ता", "कुत्ती", "सूअर"
    ];
    const isAbusive = blockedTerms.some((term) => {
      const foldedTerm = term.normalize("NFKD").toLocaleLowerCase("en")
        .replace(/[\u0300-\u036f]/gu, "").replace(/[^\p{L}\p{N}]+/gu, " ").trim();
      return foldedName.split(" ").includes(foldedTerm) ||
        (foldedTerm.includes(" ") && foldedName.includes(foldedTerm)) ||
        (foldedTerm.length >= 5 && compactName.includes(foldedTerm.replace(/\s+/gu, "")));
    });
    if (
      normalizedName.length > 40 ||
      (normalizedName.length > 0 &&
        (!/^[\p{L}\p{N}][\p{L}\p{M}\p{N} .’'-]{0,39}$/u.test(normalizedName) || isAbusive))
    ) {
      return jsonResponse(400, {
        error: "Use 1–40 letters, numbers, spaces, apostrophes, periods, or hyphens for the display name",
      });
    }
    displayName = normalizedName || null;
  }
  if (
    body.showNamePartially !== undefined &&
    typeof body.showNamePartially !== "boolean"
  ) {
    return jsonResponse(400, { error: "Invalid partial-name setting" });
  }

  const transactionId = body.transactionId.trim().toUpperCase();
  const { data: entitlement, error: entitlementError } = await supabase
    .from("premium_entitlements")
    .select("device_hash")
    .eq("device_hash", deviceHash)
    .maybeSingle();
  if (entitlementError) {
    return jsonResponse(500, { error: "Could not check existing entitlement" });
  }
  if (entitlement) {
    return jsonResponse(409, { error: "Ad-free premium is already active on this device" });
  }

  const { data: manualGrant, error: manualGrantError } = await supabase
    .from("premium_reference_grants")
    .select("transaction_id, claimed_device_hash")
    .eq("transaction_id", transactionId)
    .maybeSingle();
  if (manualGrantError) {
    return jsonResponse(500, { error: "Could not check the submitted payment reference" });
  }
  if (manualGrant) {
    if (
      manualGrant.claimed_device_hash !== null &&
      manualGrant.claimed_device_hash !== deviceHash
    ) {
      return jsonResponse(409, { error: "This payment reference has already been connected" });
    }
    const { data: claimed, error: claimError } = await supabase.rpc(
      "claim_premium_reference",
      {
        p_reference: transactionId,
        p_claimed_device_hash: deviceHash,
      },
    );
    if (claimError) {
      return jsonResponse(500, { error: "Could not activate this premium reference" });
    }
    if (claimed !== true) {
      return jsonResponse(409, { error: "This payment reference is already connected or this device is already premium" });
    }
    return jsonResponse(200, {
      status: "approved",
      message: "Premium activated successfully on this device",
    });
  }

  const { error } = await supabase
    .from("premium_requests")
    .insert({
      transaction_id: transactionId,
      device_hash: deviceHash,
      display_name: displayName,
      display_name_partial: displayName !== null && body.showNamePartially === true,
    });

  if (error?.code === "23505") {
    return jsonResponse(409, {
      error: "This payment reference was already submitted",
    });
  }
  if (error) {
    return jsonResponse(500, { error: "Could not submit payment for review" });
  }
  return jsonResponse(200, { status: "pending" });
});
