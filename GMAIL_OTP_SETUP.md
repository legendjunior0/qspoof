# Gmail OTP setup for QSpoof

The premium function now uses Supabase Auth OTP by default. Gmail sends the code through Supabase Auth's custom SMTP configuration. The existing Brevo HTTP sender remains available by setting the Edge Function secret `APP_EMAIL_PROVIDER` to `brevo`; without that setting, the function uses `supabase_auth`.

## Gmail account setup

1. Use the Gmail account whose address should appear as the sender.
2. Turn on Google 2-Step Verification.
3. In Google Account **Security → App passwords**, create an app password for QSpoof and copy the generated password. If Google does not offer App passwords for that account, Gmail SMTP with an app password is unavailable for that account.
4. Never put the app password in Android code, the admin website, a migration, or chat.

## Supabase SMTP settings

In the QSpoof Supabase project, open **Authentication → SMTP Settings** and enable custom SMTP. Enter:

| Setting | Value |
|---|---|
| Host | `smtp.gmail.com` |
| Port | `465` (SSL) or `587` (STARTTLS) |
| Username | Full Gmail address |
| Password | Gmail app password |
| Sender email | Same full Gmail address |
| Sender name | `QSpoof` |

Save the SMTP configuration. A custom domain is not required to use Gmail SMTP, though messages sent from a personal Gmail account use Gmail's own sender reputation and limits.

## Make Supabase send a numeric code

In **Authentication → Email Templates**, edit both the **Confirm signup** and **Magic Link** templates to display `{{ .Token }}` as a code. The app asks the user to enter a six-digit code; a template containing only `{{ .ConfirmationURL }}` will send a link instead.

Set the email OTP expiry to 600 seconds (10 minutes) in the Authentication email settings so it matches the app's pending-verification window. Save the setting.

## Provider selection and test

The deployed premium Edge Function defaults to `supabase_auth`; no Gmail credential belongs in Edge Function Secrets. Request a code from the companion app's profile page, check inbox and spam, enter the code, then save the profile. Supabase Auth verifies the code, while the premium function links the verified email to that device and keeps the existing limit of one send per minute and five sends per device per day.

To switch back to the retained Brevo implementation later, set Edge Function secret `APP_EMAIL_PROVIDER=brevo` and ensure `BREVO_API_KEY` and `BREVO_SENDER_EMAIL` are set. This selection takes effect without changing the app binary.

## Companion APK signing

Install the main app and companion together using builds signed with the same release keystore. Android scopes `ANDROID_ID` to the app signing key, Android user, and device; using different signing keys can make the companion appear as a different device to the premium backend. The source tree currently does not contain the release keystore, so a production-signed APK requires the same key used for the existing main app.
