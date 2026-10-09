# Dosely — Google Play Store Assets

Everything a Play Console listing needs, generated from this repo.

| Asset | Path | Spec |
|---|---|---|
| App icon | `store_assets/icons/icon_512.png` | 512×512 PNG (32-bit) |
| Feature graphic | `store_assets/feature/feature_1024x500.png` | 1024×500 PNG |
| Promo video | `store_assets/video/dosely_promo.mp4` | 1080×1920, 30 fps, 50 s, H.264 + AAC narration, burned captions, icon + feature graphic burned into intro/outro |
| Phone screenshots | `store_assets/screenshots/01…06.png` | 1080×1920 PNG (6), captioned + framed |
| Listing texts | `store_assets/listing/` | EN master + 15 localized variants |
| Raw device captures | `store_assets/raw/` | unframed 1008×2192 source shots |

Regenerate everything: `python store_assets/make_assets.py && python store_assets/make_video.py`
(requires PIL, ffmpeg, and `adb` with the device connected for fresh raw shots).

## Screenshot captions (EN)

1. Your whole GLP-1 journey, in one calm place
2. Log injections in seconds — sites, doses, notes
3. Calendar & insights that actually motivate you
4. Weight trends in kg or lb, with goal projections
5. An on-device AI coach. Private. Offline. Free.
6. Pen-stock alerts, reminders & 59 languages

## Promo video scene/narration script (EN)

| Time | Visual | Narration (TTS, burned captions) |
|---|---|---|
| 0:00–0:06 | Intro card (icon + wordmark) | Meet Dosely. Your private companion for the GLP-1 journey. |
| 0:06–0:16 | Home screen (Ken Burns) | Log every injection in seconds. Dosely tracks your dose schedule and pen stock, and reminds you before you run out. |
| 0:16–0:22 | Doses + calendar side-by-side | See adherence, streaks, and insights on a beautiful calendar. |
| 0:22–0:29 | Weight screen | Watch your progress in kilograms or pounds, with goal projections that keep you motivated. |
| 0:29–0:39 | Coach screen | And when questions come up, ask your on-device AI coach. Private, offline, and never a replacement for your doctor. |
| 0:39–0:44 | Settings | 59 languages. Built-in reminders. |
| 0:44–0:50 | Outro end card (feature graphic burned in) | Dosely. Track every dose. Every pound. Every week. Download now on Google Play. |

## Publishing (GitHub Actions)

The signed release bundle is built and uploaded by the **Publish to Google Play** workflow
(`.github/workflows/publish-google-play.yml`, manual trigger: Actions → Publish to Google Play
→ Run workflow). Inputs: track, status (`draft` stages the release for review in Play Console;
`completed` rolls it out), `version_code` (must be higher than every code in App bundle
explorer — Play refuses a reused one), `version_name`, release notes. Signing and the Play
service account come from the repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_PASSWORD`, `KEY_ALIAS`, `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`; the workflow strips a BOM /
stray whitespace from pasted secrets. `publish_play_store.py` remains the local tool for
listings, graphics and the Wear OS bundle.

## Play Console placement

- **Main store listing → App icon**: `icons/icon_512.png`
- **Main store listing → Feature graphic**: `feature/feature_1024x500.png`
- **Main store listing → Phone screenshots**: all 6 from `screenshots/`
- **Main store listing → Promo video**: upload `dosely_promo.mp4` to YouTube (unlisted) and paste the URL — Play does not accept direct video uploads.
- **Localized listings**: store listing translations are added per-language under "Store listing → Translations". Manage via Play Console UI or upload the CSV from `listing/dosely_play_listing_translations.csv`.
