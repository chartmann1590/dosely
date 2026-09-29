# Security Policy

## Supported versions

| Version | Channel | Supported |
|---|---|---|
| latest `main` branch | source | ✅ |
| Google Play build | production | ✅ (security & privacy fixes ship via store updates) |
| older releases | — | ❌ (update to the latest) |

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Email **me@charleshartmann.com** with:

- A description of the issue and its impact
- Steps to reproduce (or a PoC)
- Affected version/commit (`git rev-parse HEAD` or the Play version name)

You will get an acknowledgement within **72 hours**, and a fix timeline within
**7 days** for confirmed issues. Credit in the changelog is offered by default
(opt out in your report).

## Scope

In scope:

- The Android app in this repository (`app/`), including reminder scheduling, widget,
  AI-coach engine integration, translation layer and ad/consent integration
- Anything that could expose health data stored on device
- The GitHub Pages website (only serves static content from this repo)

Out of scope:

- Google Play Store, AdMob, UMP, ML Kit, or Gemma model infrastructure (report to Google)
- Vulnerabilities requiring a compromised device or rooted OS
- Social engineering of end users

## Design notes for reviewers

Dosely is intentionally minimal in its data footprint, which limits attack surface:

- Health data lives in a local Room database in the app's private storage
  (`databases/dosely.db`). No `android:exported` components expose it; no
  `ContentProvider` is declared.
- The AI coach runs Google's Gemma fully on-device via LiteRT-LM. No prompt or response
  is transmitted; the model file is downloaded over HTTPS from Hugging Face at the
  user's request and stored in app-private external storage.
- The only network calls are: model/pack downloads (HTTPS), Google UMP consent,
  and AdMob ad serving (governed by consent). No analytics, no crash-reporting SDK,
  no custom backend exists.
- Reminders use WorkManager/AlarmManager locally; no push service receives user data.
