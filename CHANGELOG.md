# Changelog

All notable changes to Dosely are documented here.

## [Unreleased] — Google Play release preparation

### Added
- AdMob banner ads behind Google UMP consent (GDPR/EEA + US state regulations),
  with an in-app **Privacy options** entry point where required.
- In-app **Terms of Service** and **Privacy Policy** screens (also in
  [TERMS_OF_SERVICE.md](TERMS_OF_SERVICE.md) / [PRIVACY_POLICY.md](PRIVACY_POLICY.md)).
- **Report & remove** action on every AI-coach message (Play generative-AI policy).
- Play Store assets: icon, feature graphic, captioned screenshots, TTS-narrated
  promo video, and listing texts in 17 locales (`store_assets/`).
- Project website (GitHub Pages) with docs and sponsor link.

### Fixed
- AI coach: switched Gemma catalog to the CPU-capable base model
  (`gemma-4-E2B-it.litertlm`) after the GPU-only variant failed to load; live chat
  verified end-to-end.
- Keyboard no longer leaves a gap above the chat input (zero-inset Scaffold +
  auto-hiding bottom bar + `imePadding`).
- Ad platform integration compile fixes (AdSize API, AdView setter, ThemeMode import).

## [1.0.0-alpha] — initial feature-complete build

### Added
- Onboarding (8 stages): medication, schedule, pen stock, weight + units (kg/lb),
  language, AI model download, notifications.
- Injection tracking: log/skip/delete, site + notes, titration-aware suggestions
  (semaglutide, tirzepatide, dulaglutide, liraglutide, CagriSema).
- Pen stock with automatic deduction, low/out-of-stock alerts and refill reminders.
- Weight journey: kg/lb, goal, 7-day average, trend chart, history.
- Calendar & insights: month grid, streaks, adherence, projected goal date.
- On-device AI coach: Gemma 4 (LiteRT-LM, CPU), streaming chat, stats-aware answers,
  clear medical disclaimers.
- Whole-app on-device translation via ML Kit (59 languages), persisted across restarts.
- Reminders: dose, refill, weekly weigh-in (WorkManager + boot receiver).
- Glance home-screen widget with next dose, stock and weight; in-app pin request.
- Light/dark/system themes, animated navigation transitions.
