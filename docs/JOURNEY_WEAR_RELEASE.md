# Journey, Wear OS, and ad-free subscription

## What this change provides

The visual direction is an original Dosely design informed by the workflows in
[Shotsy's public feature overview](https://shotsyapp.com/), checked October 5, 2026.
It does not use Shotsy's branding, artwork, or proprietary calculation model.

| Workflow | Implementation |
| --- | --- |
| Daily summary | Teal treatment card, next scheduled date, educational medication estimate, weight sparkline, quick journal entry point |
| Shot history | Dose, notes, exact date/time, backdating, editing, skipping, deletion |
| Injection rotation | Left/right abdomen, thigh, upper arm, previous site and next site selection |
| Treatment planning | User-entered dated plans, custom 1–365 day interval, daily reminder checks |
| Weight | Current/change/goal stats, 30/90/all chart, daily logs, Health Connect import |
| Symptoms | Named symptom and severity, dated check-ins, 30-day frequency summaries |
| Daily metrics | Water, calories, protein, appetite, food noise, notes |
| Progress photos | User-selected photos retained through document permissions; edit/remove attachment |
| Results | Weight chart, hydration chart, site/dose counts |
| Sharing | User-initiated PDF and spreadsheet-safe CSV export via Android's document picker |
| Watch | Wear OS shot, weight and water logging, local offline queue, phone acknowledgments |
| Monetization | Consent-gated non-personalized banners, monthly Play subscription, restore/manage |
| Existing features | Calendar, reminders, inventory, widget, on-device coach, translations |

This is not a verified claim of complete Shotsy parity. Shotsy's platform-specific
features differ. The medication model here is an elimination-only approximation,
not Shotsy's absorption model. Oral regimens, simultaneous medication schedules,
automatic bidirectional Health Connect sync, cloud-account sync, photo collages,
and watch complications are not implemented. Health Connect weight import is
explicitly initiated and covers the last 30 days. New screens currently use English
copy; existing localization remains in place.

## Medication model

Amounts decay according to dose × 0.5^(elapsed hours / half-life hours), separately
for each medication. Skips, invalid values and future doses are excluded. The
projection includes no unrecorded future injections. There is no estimate for
cagrilintide/combination products. This is not a blood concentration measurement
and must not be used to select a dose or change treatment.

Half-life references: [semaglutide, about one week](https://www.accessdata.fda.gov/drugsatfda_docs/label/2022/209637s012lbl.pdf),
[tirzepatide, about five days](https://www.accessdata.fda.gov/drugsatfda_docs/label/2024/217806s013lbl.pdf),
[dulaglutide, about five days](https://www.accessdata.fda.gov/drugsatfda_docs/label/2026/125469s065lbl.pdf),
[liraglutide, about thirteen hours](https://www.accessdata.fda.gov/drugsatfda_docs/label/2020/206321s012s013s014lbl.pdf).

## Wear OS release and synchronization

Build with `./gradlew :wear:assembleDebug`. Install its APK on a **separate Wear OS
device**, not on the phone. Both packages use `com.charles.dosely` and must have
matching signing certificates. Configure the phone's existing release signing
environment for the watch too, and publish the watch bundle under the same Play
application using its Wear OS form-factor track.

The [Wear OS Data Layer](https://developer.android.com/training/wearables/data/sync)
manages transport. Paired nearby devices can use Bluetooth; Google Play services
may use Wi-Fi/cloud transport when needed. This is not a Bluetooth-only custom
GATT protocol. Users do not grant a new nearby-device permission to this app.

- Shared versioned payloads are in `:sync`.
- Watch events have UUIDs and are persisted before transmission.
- Phone imports and receipts are written in a Room transaction.
- A DataStore marker makes inventory consumption safe to retry after a crash.
- Phone acknowledges only after both writes succeed.
- Replays do not recreate a deleted record; older watch weights cannot overwrite
  newer phone weights for the same day.
- Listener filters prevent local snapshot writes from causing sync loops.
- Watch shows pending items until phone acknowledgment, not merely a successful
  Data Layer enqueue.
- Clearing watch data before acknowledgment loses pending entries.

## Configure the $0.99 monthly subscription

Create subscription **dosely_ad_free**, with an auto-renewing base plan **monthly**
and period **P1M** in Play Console. Set the US price to **USD 0.99/month**, configure
regional prices, and activate it in an internal test track first. The app displays
Google Play's localized price and uses the returned offer token. There is no fake
checkout or local premium toggle.

Only PURCHASED entries for this product grant ad-free status; pending payment
does not. Purchases are acknowledged and ownership is queried again on foreground
and Restore. A cached entitlement prevents ads returning solely because the phone
is offline. Expiration/refund is reflected after the next successful Play query.
Both AdMob and the cross-promotion row are hidden for subscribers.

The client uses Google's billing service as its source of truth; this repository
does not add backend purchase-token verification or Real-time Developer
Notifications. Those can be added for stronger fraud protection and server-side
entitlement monitoring.

Debug builds **always** use Google's test ad application and unit IDs. Release
builds retain the existing AdMob environment configuration and UMP consent flow.
No dose, symptom, weight, photo or journal data is supplied to ads or billing.

An ADB-sideloaded APK alone cannot prove real purchases, renewal, refunds,
restoration across Play accounts, or production ad fill. Validate those with Play
license testers and the activated subscription before release.

## Data and verification

Room upgrades preserve version-1 history and add journal/receipt tables in v2,
then treatment plans in v3. There is no destructive migration fallback.
Instrumentation tests cover v1 migration, duplicate/concurrent delivery,
inventory, delayed weights and deletion replay.

Build/check:

```text
./gradlew :app:assembleDebug :wear:assembleDebug :app:testDebugUnitTest :sync:testDebugUnitTest :app:lintDebug :wear:lintDebug :app:assembleDebugAndroidTest
```

Run instrumentation tests only on a test emulator: inventory tests use the test
installation's preferences. Do not run them on a user's production installation.
Physical paired-watch Bluetooth testing, Play purchase tests, and Pixel 8 Pro
deployment must be recorded separately from emulator testing.
