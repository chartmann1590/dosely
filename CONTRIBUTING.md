# Contributing

Thanks for your interest in Dosely! This guide gets you from clone to running app.

> **Note:** Dosely is proprietary (see [LICENSE](LICENSE)). By contributing, you agree
> your contributions are licensed under the project's license, and you grant the
> copyright holder a perpetual, irrevocable right to use them in the app.

## Toolchain (verified versions)

| Tool | Version |
|---|---|
| JDK | 17+ |
| Android SDK | compileSdk 36, minSdk 26 |
| AGP | 8.13.0 |
| Kotlin | 2.4.20 (K2) |
| Gradle | 8.14.4 (local `./gradlew` wrapper may be older — use a recent Gradle) |
| Compose BOM | 2025.11.00 |

```bash
# build
export ANDROID_HOME=/path/to/Android/Sdk
gradle :app:assembleDebug --no-daemon

# install on a device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project layout

```
app/src/main/java/com/dosely/app/
├── ai/            # Gemma model catalog, LiteRT-LM engine, download manager
├── ads/           # UMP consent + AdMob plumbing
├── data/          # Room DB (dosely.db), repositories, DataStore settings
├── di/            # Koin modules
├── domain/        # Pure logic: dose engine, insights, units
├── reminder/      # WorkManager reminders + boot receiver
├── translate/     # ML Kit whole-app translation (S()/L() layer)
├── ui/            # Compose screens (home, doses, calendar, weight, coach,
│                  #   settings, onboarding, legal) + theme
└── widget/        # Glance home-screen widget
```

## Conventions

- **Strings**: never hardcode user-facing text — use `S("key")` in composables (async,
  translation-aware) or `L(context, "key")` in workers. Add keys to
  `res/values/strings.xml`.
- **DI**: register ViewModels/services in `di/AppModule.kt` (Koin).
- **Theme**: use the palette in `ui/theme/Color.kt` (Forest/Mint/Cream family) — no raw
  colors in screens.
- **Insets**: content must never sit under system bars; follow the Scaffold +
  `imePadding()` pattern in `ui/AppRoot.kt` (see keyboard-gap fix history).
- **Health claims**: any AI-adjacent feature needs a visible "not a doctor" treatment
  and a report path — this is a Play policy requirement, not optional.

## Testing changes

Manual e2e on a device is the current bar (unit tests roadmap item):

1. Install, complete onboarding (or keep existing data)
2. Verify: dose logging, stock deduction, weight entry (kg/lb), calendar insights,
   coach chat (report a message), language switch, widget pin, dark/light theme
3. Confirm zero crashes: `adb logcat | grep -E "FATAL|AndroidRuntime"`

## Pull requests

1. Branch from `main`, keep PRs focused (one feature/fix per PR)
2. Ensure the project builds cleanly — no new compiler warnings
3. Describe **what** changed and **why**; screenshots for UI changes
4. CI-light checklist runs on humans: run the manual e2e list above

## Reporting bugs

Open a GitHub issue with device model, Android version, steps, and expected vs actual
behavior. **Security issues → email, not issues** (see [SECURITY.md](SECURITY.md)).
