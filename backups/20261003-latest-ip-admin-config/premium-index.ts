import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const jsonResponse = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });

const publicApiKey = "sb_publishable_hi9tDuZNDMr8vF0vAwF5wg_s-XsIn6J";

async function authorizeAdmin(
  supabase: ReturnType<typeof createClient>,
  email: unknown,
  password: unknown,
): Promise<boolean> {
  if (
    typeof email !== "string" ||
    typeof password !== "string" ||
    email.length > 254 ||
    password.length < 1 ||
    password.length > 256
  ) return false;

  const { data, error } = await supabase.rpc(
    "verify_premium_admin_password",
    { candidate_email: email, candidate_password: password },
  );
  return !error && data === true;
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (request.method !== "POST") {
    return jsonResponse(405, { error: "Method not allowed" });
  }
  if (request.headers.get("apikey") !== publicApiKey) {
    return jsonResponse(401, { error: "Invalid API key" });
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !serviceRoleKey) {
    return jsonResponse(500, { error: "Premium service is not configured" });
  }

  let body: {
    action?: unknown;
    transactionId?: unknown;
    androidId?: unknown;
    requestId?: unknown;
    adminEmail?: unknown;
    adminPassword?: unknown;
    displayName?: unknown;
    showNamePartially?: unknown;
    showName?: unknown;
    adsEnabled?: unknown;
    premiumOnlyAccess?: unknown;
    deviceModel?: unknown;
    ramMb?: unknown;
    storageMb?: unknown;
    cpu?: unknown;
  };
  try {
    body = await request.json();
  } catch {
    return jsonResponse(400, { error: "Invalid JSON body" });
  }

  const supabase = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  if (body.action === "public-config") {
    const { data, error } = await supabase.rpc("get_premium_public_settings");
    if (error) return jsonResponse(500, { error: "Could not load public settings" });
    if (!data || typeof data.adsEnabled !== "boolean") {
      return jsonResponse(500, { error: "Public settings are not configured" });
    }
    if (typeof data.premiumOnlyAccess !== "boolean") {
      return jsonResponse(500, { error: "App access settings are not configured" });
    }
    return jsonResponse(200, data);
  }

  if (
    body.action === "admin-ads-get" ||
    body.action === "admin-ads-set" ||
    body.action === "admin-app-access-get" ||
    body.action === "admin-app-access-set" ||
    body.action === "admin-premium-list" ||
    body.action === "admin-premium-add" ||
    body.action === "admin-premium-remove" ||
    body.action === "admin-clear-data"
  ) {
    if (!await authorizeAdmin(supabase, body.adminEmail, body.adminPassword)) {
      return jsonResponse(403, { error: "Admin authorization required" });
    }

    if (body.action === "admin-ads-get") {
      const { data, error } = await supabase.rpc("get_premium_public_settings");
      if (error) return jsonResponse(500, { error: "Could not load ad settings" });
      return jsonResponse(200, { adsEnabled: data?.adsEnabled === true });
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

    if (body.action === "admin-app-access-get") {
      const { data, error } = await supabase.rpc("get_premium_public_settings");
      if (error || typeof data?.premiumOnlyAccess !== "boolean") {
        return jsonResponse(500, { error: "Could not load premium-only access setting" });
      }
      return jsonResponse(200, { premiumOnlyAccess: data.premiumOnlyAccess });
    }

    if (body.action === "admin-app-access-set") {
      if (typeof body.premiumOnlyAccess !== "boolean") {
        return jsonResponse(400, { error: "Choose whether app access requires premium" });
      }
      const { data, error } = await supabase.rpc(
        "set_premium_only_access",
        { enabled: body.premiumOnlyAccess },
      );
      if (error || typeof data !== "boolean") {
        return jsonResponse(500, { error: "Could not update premium-only access setting" });
      }
      return jsonResponse(200, { premiumOnlyAccess: data });
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
      .not("claimed_at", "is", null)
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

  if (body.action === "admin-login") {
    const authorized = await authorizeAdmin(
      supabase,
      body.adminEmail,
      body.adminPassword,
    );
    return authorized
      ? jsonResponse(200, { authorized: true })
      : jsonResponse(401, { error: "Invalid admin email or password" });
  }

  if (body.action === "admin-analytics") {
    if (!await authorizeAdmin(supabase, body.adminEmail, body.adminPassword)) {
      return jsonResponse(403, { error: "Admin authorization required" });
    }
    const { data, error } = await supabase.rpc("get_app_usage_analytics", {
      p_days: 30,
    });
    if (error || !data) {
      return jsonResponse(500, { error: "Could not load app analytics" });
    }
    return jsonResponse(200, { analytics: data });
  }

  if (body.action === "admin-list") {
    if (!await authorizeAdmin(supabase, body.adminEmail, body.adminPassword)) {
      return jsonResponse(403, { error: "Admin authorization required" });
    }
    const { data, error } = await supabase
      .from("premium_requests")
      .select("id, transaction_id, submitted_at, status, display_name, display_name_partial")
      .eq("status", "pending")
      .order("submitted_at", { ascending: true })
      .limit(100);
    if (error) return jsonResponse(500, { error: "Could not load pending requests" });
    return jsonResponse(200, { requests: data ?? [] });
  }

  if (
    body.action === "admin-list-active" ||
    body.action === "admin-revoke" ||
    body.action === "admin-approve-reference" ||
    body.action === "admin-approve" ||
    body.action === "admin-reject"
  ) {
    if (!await authorizeAdmin(supabase, body.adminEmail, body.adminPassword)) {
      return jsonResponse(403, { error: "Admin authorization required" });
    }

    if (body.action === "admin-list-active") {
      const { data, error } = await supabase
        .from("premium_entitlements")
        .select("transaction_id, granted_at")
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
            .select("transaction_id, display_name, display_name_partial")
            .in("transaction_id", references)
            .not("display_name", "is", null),
          supabase
            .from("premium_reference_grants")
            .select("transaction_id, display_name, display_name_partial")
            .in("transaction_id", references)
            .not("display_name", "is", null),
        ]);
      if (requestsError || grantsError) {
        return jsonResponse(500, { error: "Could not load active donor names" });
      }

      const namesByReference = new Map((requests ?? []).map((item) => [
        item.transaction_id,
        { display_name: item.display_name, display_name_partial: item.display_name_partial },
      ]));
      for (const grant of grants ?? []) {
        namesByReference.set(grant.transaction_id, {
          display_name: grant.display_name,
          display_name_partial: grant.display_name_partial,
        });
      }
      return jsonResponse(200, {
        entitlements: entitlements.map((item) => ({
          ...item,
          ...namesByReference.get(item.transaction_id),
        })),
      });
    }

    if (body.action === "admin-revoke") {
      if (
        typeof body.transactionId !== "string" ||
        !/^[A-Za-z0-9-]{6,80}$/.test(body.transactionId.trim())
      ) {
        return jsonResponse(400, { error: "Enter a valid payment reference ID" });
      }
      const { data, error } = await supabase
        .from("premium_entitlements")
        .delete()
        .eq("transaction_id", body.transactionId.trim().toUpperCase())
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
      body.androidId.length < 8 ||
      body.androidId.length > 128
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
      p_android_id: body.androidId,
      p_model: hasTelemetry ? body.deviceModel : null,
      p_ram_mb: hasTelemetry ? body.ramMb : null,
      p_storage_mb: hasTelemetry ? body.storageMb : null,
      p_cpu: hasTelemetry ? body.cpu : null,
    });
    if (error || !data || typeof data.accessAllowed !== "boolean") {
      return jsonResponse(500, { error: "Could not verify app access" });
    }
    return jsonResponse(200, data);
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
    const { data, error } = await supabase.rpc("delink_premium_device", {
      p_device_hash: deviceHash,
      p_transaction_id: entitlement.transaction_id,
    });
    if (error || typeof data !== "boolean") {
      return jsonResponse(500, { error: "Could not turn off premium on this device" });
    }
    return jsonResponse(200, { delinked: data });
  }

  if (body.action !== "submit") {
    return jsonResponse(400, { error: "Unknown action" });
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
