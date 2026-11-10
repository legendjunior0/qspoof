# Supabase premium entitlement setup

The QSpoof Supabase project is `aearzrtzrtyvuywjcwni`. The Android build defaults point to this project. Apply pending migrations and deploy the matching Edge Functions before using newly added remote features.

Supabase's free tier is a reasonable starting point for a small, manually reviewed premium flow, subject to its current quotas and inactivity policies. To recreate the setup in another project:

1. Apply pending migrations with the Supabase CLI from the project root. The CLI compares `supabase/migrations/` with the remote migration history and applies only missing migrations. Use the dry run first; do not mark migrations as applied or repair migration history unless you have confirmed the remote schema already contains those exact changes.
2. Deploy `supabase/functions/premium/index.ts` as the existing `premium` function. It uses a public API key check rather than JWT validation because the mobile app uses a modern publishable key. That key is public and can be extracted from the app; it is not user authentication. The tables remain protected by RLS, and only the Edge Function uses the service-role key.
3. Deploy `supabase/functions/premium-config/index.ts` as `premium-config` with JWT verification disabled. This separate function handles public app configuration and admin-managed access intervals, ad policy, popups, and uploads; its admin actions verify the existing admin session and permissions. Deploy it separately rather than replacing `premium`, so the legacy premium and payment routes remain unchanged.
4. Supabase provides `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` to each function. The HMAC secret is generated and kept in the inaccessible `private.premium_config` table. Never put the service-role key in the Android app.
5. For email verification, add `BREVO_API_KEY`, `BREVO_SENDER_EMAIL`, and optionally `BREVO_SENDER_NAME` as Edge Function secrets. Use a verified sender/domain in Brevo; do not put these secrets in the app or web page.
6. The Android build has this project's URL and public publishable key as defaults. They are intentionally public app configuration, not secrets. You can override them for another project:

   ```sh
   ./gradlew :app:assembleDebug \
     -PsupabaseUrl=https://YOUR_PROJECT_REF.supabase.co \
     -PsupabasePublishableKey=YOUR_PUBLIC_PUBLISHABLE_KEY
   ```

   Use a public publishable key (not a secret or service-role key). The database has no client table policies; requests must go through the Edge Function.
## Reviewing and approving payments

The project includes a private-use browser admin page at `admin/premium.html`.

Admin accounts are stored in Supabase. Use the owner account configured for this project; no admin password is embedded in the HTML page. Five failed password attempts lock that email for 15 minutes.

The admin page keeps the password in memory only while signed in and sends it over HTTPS for each admin action. Sign out when finished. Owners can manage delegated admin accounts and permissions from the Profile section.

From the project root, serve the page locally with `python3 -m http.server 8000`, then open `http://localhost:8000/admin/premium.html`. Sign in and refresh the pending list. Stop the server when finished.

You can approve a pending request by selecting it or by entering its exact transaction reference in **Approve by transaction reference**. This matches a pending app submission and grants premium to its associated device. It does **not** independently verify the payment with UPI; confirm the payment yourself before approving. The **Active premium** tab lists active grants, and **Reject** closes a pending request without granting premium.

To revoke a grant, open **Active premium** and choose **Remove premium access** beside its transaction reference. This removes the active entitlement immediately while preserving the approved payment request in history. The device can then submit a new transaction reference for review.

Alternatively, list requests in the Supabase SQL editor:

```sql
select id, transaction_id, submitted_at
from public.premium_requests
where status = 'pending'
order by submitted_at;
```

After verifying a payment, approve the matching request:

```sql
select public.approve_premium_request('REQUEST_UUID');
```

A `true` result means the entitlement was granted. A `false` result means the request was already processed or its device/payment conflicts with an existing entitlement.

The app sends Android's app-scoped `ANDROID_ID` at startup to check access, and sends the payment reference ID with it on submission. Android does not require storage permission for this identifier; it normally survives reinstall for the same app signing key and Android user, but can change after factory reset, user/profile changes, or signing-key changes. The database stores an HMAC hash of the identifier rather than the raw value. A device may have multiple pending reference requests; approving one connects that reference to the device and closes its other pending requests. A manually registered premium reference activates when entered in the app and can be claimed once.

Donors may optionally submit a display name with a payment reference and choose to show it partially. Names submitted with app requests appear after approval; names attached to manually registered references appear according to the admin's display setting. Partial names are masked by the Edge Function before they are returned. Apply pending migrations and deploy the relevant Edge Functions, including `premium-config` for the new configuration controls.

The tiered access settings migration starts normal and premium access checks at six hours each; administrators can set each interval independently from 1 to 168 hours. The app checks with the server at launch, retries a failed check once, and can use a valid offline grant until its configured expiry. The offline grant is encrypted with an Android Keystore key; this raises the bar for editing app data but cannot make a rooted or patched device tamper-proof.

The separate **Promotional popups** page supports optional popups with a default preset, editable title/message/button text, link or download actions, per-device display caps, and cooldowns. Popup images are resized and compressed in the browser before upload through a short-lived signed URL to the public `app-promotions` bucket, limited to JPEG, PNG, or WebP images up to 8 MB. APK releases continue to use the private `app-updates` bucket.

The **Updates** page publishes package-specific signed APKs to the private Supabase `app-updates` bucket and can store a separate external direct-APK fallback URL for each package. Supported package IDs are `com.antutu.ABenchMark` (QEngine), `com.antutu.benchmark.full` (QSpoof), and `com.ludashi.benchmark` (Ludashi); publish each APK under its matching ID. The Android app downloads with the system Download Manager, shows its download notification, verifies the APK SHA-256, and offers an **Install update** action. Android still requires the user to allow installs from the app (once) and confirm installation. After starting a download, the app opens the Telegram channel configured under **Links**; that promotion destination is separate from the external APK fallback.

App downloads stream through the `premium` Edge Function rather than exposing a signed Storage URL. The function reserves each requested byte range against a monthly APK-download budget before streaming and redirects to the external fallback when the budget is exhausted or the Supabase transfer fails. The reservation is atomic across concurrent downloads; interrupted transfers conservatively retain their reservation for that month.

The Updates page's **Total project monthly bandwidth (GB)** defaults to 10 GB, matching the current Free plan's documented total allowance (5 GB cached plus 5 GB uncached). The APK route reserves 40% of that configured total (4 GB by default). Change the value if the project plan changes. This meter limits APK-download traffic, not other database, Storage, or Functions traffic, so it cannot guarantee the entire project stays below its plan quota. See [Supabase bandwidth and Storage egress](https://supabase.com/docs/guides/storage/serving/bandwidth).

When a newer release is available, users at or above the minimum see the dismissible popup once for that release. Users below the minimum cannot continue until the update is installed. The minimum-version field of `0` disables the forced update.

App access is still checked with Supabase at every launch. The encrypted, expiring premium cache is used only when online checks fail, preserving configured offline access; clearing it on every launch would disable that behavior and increase the chance of lockouts.

Deploy the companion function with the project's Supabase CLI v3 syntax:

```sh
supabase functions deploy premium-config --remote=aearzrtzrtyvuywjcwni --no-verify-jwt
```

The **Premium** menu lets an administrator pre-register payment references and directly grant premium to an OTP-verified email already linked to one device. The email grant does not create a pending payment request. Email profile details remain separate from the public donor list. The **Global advertising** control enables or disables ads for all non-premium users. The **Premium-only app access** control requires premium except for a device enrolled in the matching capped beta version. Access is checked at startup, with one retry for a failed online check; an unexpired encrypted offline grant remains usable until its configured expiry. The app opens the configured channel once after its first launch for each installed version. Admin controls for **Updates**, **Beta**, and **Links** configure minimum allowed app version/update destination, first-come non-premium beta enrollment limits, and the channel/group destinations. Premium users bypass the beta capacity cap. The admin page also provides a double-confirmed control to clear premium user records.

The community donor list reads from approved donation requests and claimed administrator-issued references, rather than current entitlements. Delinking or revoking premium therefore removes access from the device while keeping its approved donor name listed.

## App usage analytics

Apply `supabase/migrations/20261002150000_app_usage_analytics.sql`, later analytics migrations, and the additive migrations `20261004010000_app_update_beta_links_profile.sql`, `20261004020000_release_expired_email_verifications.sql`, and `20261004030000_beta_closure_audit.sql`, then deploy the current `supabase/functions/premium/index.ts` with the matching Android release. Existing premium IDs continue to use the same device HMAC and entitlement tables.

Each successful app start reports the device model, total RAM, data-partition capacity, and Android-reported SoC name. The app still starts normally when that report cannot be sent. The Edge Function converts the app-scoped Android ID to the existing server-side HMAC before storage; raw device IDs are never saved.

The Analytics tab shows seven daily bars or seven Sunday–Saturday weekly bars and can switch between all, premium, and non-premium users. It lists one current row per device and includes model, RAM, storage, name, verified email, latest IP, and premium access, with filters for the recorded device attributes. Daily usage data is retained for 35 days; weekly aggregates are retained for seven weeks.

The profile page lets a user save their name and email. This does not create a Supabase Auth account: the Edge Function uses Brevo only as an outbound OTP mail service, and the app profile remains linked to the server-side device hash. Brevo sends a six-digit email code that expires after ten minutes; there are five send attempts per device per day, at least one minute between sends, and five code-entry attempts per code. Only OTP-verified email addresses can be used for the admin's direct email grant. Admin grants bind that verified email to one device and do not create pending payment records.

The Beta tab can close enrollment and revoke access for non-premium devices running the configured beta version; those app builds receive the current channel link as the redirect destination. Beta access logs are one row per device and version and retain a short prefix of the server-side HMAC, hardware specs, IP, profile name/email, premium/access status, and first/last-seen times. Admins can export the log as CSV. The raw Android ID is not stored in beta logs.
