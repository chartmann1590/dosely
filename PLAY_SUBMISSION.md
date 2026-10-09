# Dosely — Google Play Submission Checklist

Everything below reflects the code as shipped in this repo (**test ad IDs!**).
Items marked ☐ must be done by a human in Play Console / AdMob before publishing.
Items marked ✓ are already handled in the app.

---

## 1. AdMob: unit IDs — ✓ WIRED VIA GITHUB SECRETS (never in source)

Production IDs are stored as **GitHub repo secrets** and injected at build time via
environment variables; the repository contains only Google's public test IDs as
fallbacks. Nothing is hardcoded.

| Secret name | Injected into |
|---|---|
| `ADMOB_APPLICATION_ID` | manifest placeholder `${admobApplicationId}` → `com.google.android.gms.ads.APPLICATION_ID` |
| `ADMOB_BANNER_UNIT_ID` | `BuildConfig.ADMOB_BANNER_UNIT_ID` → `AdsManager.BANNER_AD_UNIT` |
| `ADMOB_INTERSTITIAL_UNIT_ID` | `BuildConfig.ADMOB_INTERSTITIAL_UNIT_ID` (ready for interstitials) |

- Local/debug builds: Google test IDs (`ca-app-pub-3940256099942544…`) — safe.
- CI: run **Android CI → release-check** (workflow_dispatch) to build a release AAB
  with the production IDs from secrets (unsigned — sign locally or add a signing secret).
- Verify before uploading: `aapt2 dump badging app-release.aab` or bundletool manifest
  shows your `APPLICATION_ID`, and `BuildConfig` mirrors your unit IDs.
- Interstitial ID is wired end-to-end (secret → BuildConfig) but **not yet shown** in
  the UI; add a `InterstitialAd` call site when wanted — do not put the ID in code.

## 2. UMP / Consent (✓ implemented)

- ✓ `AdsManager.gatherConsent()` runs on **every launch**: `requestConsentInfoUpdate` + `loadAndShowConsentFormIfRequired`.
- ✓ Ads only load when `consentInformation.canRequestAds()` is true (banner gated behind `adsReady`).
- ✓ **Privacy options entry point** in Settings → About, shown automatically when
  `privacyOptionsRequirementStatus == REQUIRED` (EEA/UK + regulated US states). Verified hidden in non-regulated geographies.
- ☐ In AdMob → Privacy & messaging, create a GDPR message and US state regulations message so UMP has forms to serve.

## 3. Play Console — App content declarations

### Health apps declaration (required: app mentions medication/doses/weight)
☐ Complete **App content → Health apps**: declare Dosely is a **tracking/journaling utility**,
not a medical device; no diagnosis/treatment recommendations; AI coach is an offline LLM with disclaimers.

### Data safety form (answers already drafted in the in-app Privacy Policy)
- ✓ **Data collected**: Advertising ID — collected by Google's ad SDK when ads are shown; encrypted in transit; optional per user consent.
- ✓ **Data NOT collected**: health & fitness data, location, personal info, photos, contacts. All health data (injections, doses, weight, notes, AI chats) stays **on-device only**; no servers, no account, no analytics SDK.
- ☐ Mirror these answers in **App content → Data safety**:
  - "Does your app collect or share any of the required user data types?" → **Yes** (Advertising ID, for advertising, shared with Google as service provider under user consent).
  - Health & fitness → **No collection**.
  - All data in transit encrypted → **Yes**. Users can request deletion → **Yes** (clear app data / uninstall; ads data via Google Ads Settings).

### Ads declaration
☐ **App content → Ads**: "Yes, my app contains ads" (banner ads).
☐ Declare ad content rating appropriately during the questionnaire.

### AI-generated content (Play generative-AI policy)
- ✓ In-app **report/flag** on every AI message (Coach → "Report & remove" — removes the message from chat). Play requires an in-app reporting mechanism for AI content.
- ✓ AI content labeled as AI, with persistent disclaimers (Coach header, Medical disclaimer dialog, TOS §6).
- ☐ In the content questionnaire, declare AI-generated content features: conversational AI (offline Gemma model), with moderation via user reporting.

### Other required declarations
☐ **Privacy policy URL** (App content → Privacy policy): must be a public URL. The in-app Privacy Policy text can be published as a webpage — keep both in sync.
☐ **App access**: all functionality available without credentials.
☐ **Content rating** questionnaire → likely **Everyone / PEGI 3** (no user-generated content shared between users; AI is one-way).
☐ **Target audience**: 18+ (medication context); do NOT declare children as an audience.
☐ **News / COVID / government apps**: No.
☐ **Financial features**: No.
☐ **Health data sharing for AI training**: No — model runs on-device; nothing is uploaded.

## 4. Release build (REQUIRED)

✓ **CI path (preferred):** Actions → *Publish to Google Play* → Run workflow (manual only). It builds a
signed AAB from the repo secrets and uploads it to the chosen track (`draft` → send for review in
Play Console). Pick a `version_code` above the highest one in App bundle explorer.

Local path, if ever needed: debug builds are signed with the debug key and use `versionCode` from `app/build.gradle.kts`.

☐ Add a `signingConfig` for release (or opt into **Play App Signing** and upload an AAB signed with the upload key):
```bash
export ANDROID_HOME=~/AppData/Local/Android/Sdk
/c/gradle-dist/gradle-8.14.4/bin/gradle :app:bundleRelease
```
☐ Bump `versionCode` / set a proper `versionName`.
☐ Verify `android:allowBackup="true"` is acceptable for your privacy posture (health data goes to Android cloud backup) — consider `allowBackup="false"` or `dataExtractionRules` excluding the DB for a stricter stance.
☐ Upload the **AAB** (not APK) to Play Console → Production / Internal testing.

## 5. Store listing assets — ✓ ALL GENERATED, see `store_assets/README.md`

- ✓ App icon (512×512): `store_assets/icons/icon_512.png`
- ✓ Feature graphic (1024×500): `store_assets/feature/feature_1024x500.png`
- ✓ Phone screenshots (6× 1080×1920, captioned): `store_assets/screenshots/`
- ✓ Promo video (1080×1920, 50 s, TTS narration + burned captions, icon & feature graphic burned in): `store_assets/video/dosely_promo.mp4` — upload to YouTube (unlisted) and paste the URL in Play Console.
- ✓ Listing texts for 17 locales (validated ≤30/≤80/≤4000): `store_assets/listing/play_listings.md` + Play-Console CSV `store_assets/listing/dosely_play_listing_translations.csv`. The app itself translates the UI into 59 languages; the listing covers the store-facing languages.

## 6. Pre-launch report expectations

- Banner ad renders on all 6 tabs (verified on-device; test unit shows "Test Ad").
- UMP consent flow: silent when not required; form appears in EEA/UK geographies (test via `ConsentDebugSettings` + `addTestDeviceHashedId("ECE881749D58EF0DA0CED390014532FF")` if needed).
- 0 crashes in current e2e session (logcat verified).
- The 2.6 GB Gemma model is downloaded manually/in-app — do not expect pre-launch robots to download it; the Coach screen degrades gracefully ("model not downloaded yet" + Settings link).

## 7. Post-release monitoring

☐ Watch AdMob for invalid-traffic (do not click your own live ads — use the test IDs during development).
☐ Watch Android Vitals for ANRs from `CoachEngine` model load (runs off main thread; verified).
☐ Update the in-app "Last updated" date in `LegalScreen`/strings when policies change.
