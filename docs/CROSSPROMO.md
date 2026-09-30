# Hartmann Studios Dynamic Cross-Promotion Platform

Production cross-promotion system for all Hartmann Studios Android apps: every app
discovers and promotes the rest of the portfolio automatically, with nothing hardcoded.

- **Backend**: https://crosspromo.charleshartmann.com (Cloudflare Worker + KV, zero-cost tier)
- **SDK**: `hartmann-crosspromo/` Android library module (Compose + XML, minSdk 21)
- **Catalog discovery**: live Google Play developer page, refreshed by cron every 6 h

---

## Architecture

```
                         ┌────────────────────────────────────────────┐
                         │            Cloudflare Worker (API)         │
   Android apps ────────▶│  /api/v1/health        GET                 │
   (SDK, no scraping)    │  /api/v1/catalog       GET                 │
                         │  /api/v1/recommendations GET               │
                         │  /api/v1/events        POST                │
                         │  /api/v1/admin/*       token-gated         │
                         └───────────────┬────────────────────────────┘
                                         │ reads
                        ┌────────────────▼────────────────┐
                        │  KV "hartmann-crosspromo"       │
                        │  catalog:current                │
                        │  catalog:last-known-good        │
                        │  catalog:refresh-meta           │
                        │  config:engine / config:app:*   │
                        └────────────────▲────────────────┘
                                         │ writes (only after validation)
        ┌────────────────────────────────┴───────────────────────────────┐
        │  Cron (0 */6 * * *):  PlayStoreCatalogProvider.discoverApps()  │
        │  → developer page parse → merge (firstDiscoveredAt preserved)  │
        │  → enrich stale metadata from app detail pages (budgeted)      │
        │  → safety validation (0-app guard, suspicious-drop guard)      │
        │  → replace current + last-known-good                           │
        └────────────────────────────────────────────────────────────────┘
        ┌────────────────────────────────────────────────────────────────┐
        │  KV "hartmann-crosspromo-analytics": daily capped aggregates   │
        │  (impressions/clicks by target|source, placement, sdk)         │
        └────────────────────────────────────────────────────────────────┘
```

**Why KV and not D1:** the deployment API token in use covers Workers + KV (D1
admin is not granted). All analytics state is aggregate-only and every write is
batched and size-capped, which fits KV's semantics; the recommendation path is
read-only with zero Google calls, so consistency requirements are trivial.
Everything else in the original D1-based design (migrations, aggregates, CTR)
is preserved 1:1 in KV keys.

### Components

| Component | Location | Role |
|---|---|---|
| `PlayStoreCatalogProvider` | `backend/src/discovery/` | The ONLY code that touches Google. Discovery + metadata parsing with structural fallbacks. |
| Refresh pipeline | `backend/src/refresh.ts` | Merge, budget-gated enrichment, safety validation, atomic LKG swap. |
| Recommendation engine | `backend/src/engine.ts` | Popularity score, 65/35 weighted/exploration draw, per-app share cap, bounded new-app boost, per-request variety seed, dedupe. |
| API routes | `backend/src/index.ts` | Versioned endpoints, validation, rate limiting, admin auth. |
| Store | `backend/src/store.ts` | KV persistence: catalogs, config, refresh meta, capped daily aggregates. |
| Android SDK | `hartmann-crosspromo/` | API client, DataStore SWR cache, Compose + XML UI, analytics sinks, install referrer attribution. |

---

## Deployment

### One-time setup

```bash
cd backend
npm install

npx wrangler login

# KV namespaces (already created for this deployment):
npx wrangler kv namespace create hartmann-crosspromo
npx wrangler kv namespace create hartmann-crosspromo-analytics
# → put the returned ids into wrangler.jsonc (kv_namespaces)

# Admin token (never commit it):
npx wrangler secret put ADMIN_TOKEN     # paste a long random string

# Deploy worker + cron:
npx wrangler deploy
```

### Custom domain (already configured)

The worker is attached to `https://crosspromo.charleshartmann.com` via
Cloudflare Workers Custom Domains. To re-create it:

```bash
curl -X PUT -H "Authorization: Bearer $CF_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"service":"crosspromo","environment":"production","hostname":"crosspromo.charleshartmann.com"}' \
  "https://api.cloudflare.com/client/v4/accounts/$ACCOUNT_ID/workers/domains"
```

### Daily operations

```bash
npx wrangler tail crosspromo                     # structured logs
curl -s https://crosspromo.charleshartmann.com/api/v1/health
TOKEN=...                                        # from your password manager
curl -s -H "Authorization: Bearer $TOKEN" \
  https://crosspromo.charleshartmann.com/api/v1/admin/status | jq
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  https://crosspromo.charleshartmann.com/api/v1/admin/refresh   # force refresh
```

### Remote configuration (no app releases needed)

```bash
# Engine-wide config (all values optional, clamped server-side):
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"popularWeight":0.7,"randomWeight":0.3,"newAppBoostDays":10,"defaultLimit":3,"maxLimit":6}' \
  https://crosspromo.charleshartmann.com/api/v1/admin/config

# Disable cross-promo inside ONE app (kill switch):
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"packageName":"com.charles.qrcode","scope":"source","config":{"enabled":false}}' \
  https://crosspromo.charleshartmann.com/api/v1/admin/app-config

# Pause promotion of ONE target app / boost it / hide it from a source:
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"packageName":"com.charles.cruiseapp","scope":"target","config":{"enabledForPromotion":false}}' \
  https://crosspromo.charleshartmann.com/api/v1/admin/app-config
# {"promotionMultiplier":2.0} → temporary feature boost
# {"excludedFromSources":["com.charles.qrcode"]} → per-source exclusion
```

---

## How dynamic discovery works

1. The cron fetches `https://play.google.com/store/apps/developer?id=Hartmann+Studios&hl=en&gl=US`.
2. `parseDeveloperPage` extracts **canonical** `details?id=` links, validates package-id
   syntax, dedupes repeated anchors, rejects cards naming a different developer, and
   confirms the developer name appears on the page.
3. Names + icons come from the app-card markup; installs/descriptions/updated dates from
   each app's detail page (`AF_initDataCallback` `ds:5` blob with DOM fallbacks).
4. The new discovery is **merged** with the previous catalog: `firstDiscoveredAt` is
   preserved for existing apps (drives the new-app boost), and operator flags
   (`enabledForPromotion`, `promotionMultiplier`) survive refreshes forever.
5. Safety validation. A refresh that discovers **0 apps**, keeps fewer than
   `minKeepRatio` (default 50%) of the previous catalog, or produces no usable
   names/icons is **rejected**: last-known-good stays in place and diagnostics are
   stored in `catalog:refresh-meta`.

### If Google changes its pages

Only `backend/src/discovery/PlayStoreCatalogProvider.ts` (+ the parser under
`backend/src/parsers/`) ever breaks. The catalog keeps serving from
last-known-good (current API responses never fail), and diagnostics in
`/admin/status` show exactly which step failed. Fix the parser, run
`npm test` (fixtures are real captured Play HTML), redeploy, then trigger
`/admin/refresh`.

### Adding/removing apps

Nothing to do. Publish a new app → the next cron refresh discovers it, stamps
`firstDiscoveredAt`, and the new-app boost gives it 14 days of extra exposure.
Unpublish an app → it disappears from the developer page and is removed from the
catalog on the next refresh (removals are listed in diagnostics).

---

## Android integration

### 1. Add the module (once per app)

```kotlin
// settings.gradle.kts
include(":hartmann-crosspromo")

// app/build.gradle.kts
dependencies {
    implementation(project(":hartmann-crosspromo"))
    // Optional: real icons. Without it the SDK shows neutral letter avatars.
    implementation("io.coil-kt:coil-compose:2.6.0")
}
```

### 2. Initialize (Application.onCreate)

```kotlin
HartmannCrossPromo.initialize(
    application = this,
    apiBaseUrl = "https://crosspromo.charleshartmann.com",
    enableFirebaseAnalytics = true,   // only if the app already uses Firebase
    enableBackendAnalytics = true,    // batched events → /api/v1/events
)
```

The source package is ALWAYS derived automatically from `context.packageName`.
Never hardcode it.

### 3. Place the UI

```kotlin
// Compose — Settings/About (vertical list):
HartmannPromoRow(placement = "settings")

// Compose — bottom of home (horizontal carousel):
HartmannPromoCarousel(placement = "home")

// XML apps — no Compose required:
HartmannPromoViewBinder.bind("settings", findViewById(R.id.promo_frame))
```

Behavior: cached recommendations render instantly (stale-while-revalidate,
60-minute fallback TTL so picks re-roll about hourly); network failures,
empty catalogs, or disabled placements render **nothing** — never a spinner,
never an error. Icons load with Coil when the host provides it, otherwise via
the SDK's built-in cached loader. Each card shows the app's Play install
badge (e.g. `5K+`) and, when the catalog has them, rating stars. Clicks open
the official Play listing (`market://` → browser fallback); no APK downloads,
ever.

### 4. Install attribution (target app, optional)

Add `implementation("com.android.installreferrer:installreferrer:2.2")` to the
**target** app, then once at first launch:

```kotlin
 lifecycleScope.launch {
     HartmannInstallAttribution.reportCrosspromoInstallIfAttributed(this@App)
 }
```

It reads the Google Play Install Referrer (supported API only, reflection-guarded
so the dependency stays optional), parses `utm_source=<sourceApp>&utm_medium=crosspromo`
and reports a `crosspromo_install`-shaped event to your analytics sinks.

---

## Analytics & success metrics

`GET /api/v1/admin/status` returns, over a rolling 7-day window:

- totals: impressions, clicks, **CTR**
- per-target-app impressions/clicks (which apps get promoted best)
- per-source-app clicks (which apps drive the most discovery)
- per-placement impressions/clicks (where cross-promo works)
- catalog snapshot + last refresh diagnostics

Event integrity: `sourcePackage` and `targetPackage` must both be real Hartmann
catalog members, event types / placements / ranks / session ids / timestamps are
validated, payloads are size-capped, and per-IP rate limits protect the
aggregates. Nothing personal (no ad id, no device id, no fingerprinting) is ever
collected — session ids are random, short-lived UUIDs.

---

## Testing

```bash
cd backend && npm test        # 72 tests: parsers (real fixtures), engine,
                              # refresh safety, event validation,
                              # 100,000-selection simulation
./gradlew.bat :hartmann-crosspromo:testDebugUnitTest   # 21 SDK tests
```

The simulation proves at scale: the popular app leads without dominating,
every tail app receives exposure, the new-app boost is real but temporary
(21.7% vs 4.0% baseline share in the reference run), the disabled app and the
current app are never selected, and no response contains duplicates.

---

## Troubleshooting

| Symptom | Diagnosis |
|---|---|
| `/health` says `degraded` | Current catalog is empty/broken and last-known-good is serving. Check `refresh.lastResult` + `rejectReason` in `/admin/status`. |
| `lastResult: "rejected"` | Discovery safety guard tripped (Google HTML change or real catalog drop). Diagnostics list discovered/kept counts. |
| All icons identical | Content-rating icon leaked into metadata (fixed via og:image preference); trigger `/admin/refresh`. |
| No promo cards in an app | Check `config:app:<pkg>` kill switch, placements allowlist, and that the app's `sourcePackage` is NOT the only catalog entry (catalog must have ≥2 enabled apps). |
| Events rejected en masse | The app's backend copy is stale vs catalog (renamed packages?) — check rejected reasons in the events response. |

## Limitations

- Public Play metadata only: ratings/review counts are absent until an app earns
  ratings (the current young catalog has none — popularity falls back to install
  estimates; the code trusts ratings automatically once they exist).
- Install-bucket values are Google's coarse public labels (`100+`, `1K+`), not exact.
- The Play detail-page enrichment window is CPU-budgeted per refresh (~8 apps/run);
  the rest keep serving stale-but-valid metadata until subsequent cycles.
- Install attribution requires the target app to integrate the optional
  install-referrer dependency; without it, installs are simply not attributed.
