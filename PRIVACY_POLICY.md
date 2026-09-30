# Privacy Policy

**Last updated: September 30, 2026**

This Privacy Policy describes how the Dosely Android application (the "App") handles
your information. It is provided by Charles Hartmann ("we", "us").

## Overview

Dosely is built privacy-first. Your health tracking data (injections, doses, weight
entries, notes, and AI coach chats) is stored only on your device, in the app's private
storage. We do not collect, transmit, or sell this data. There is no account, no cloud
sync, and no analytics SDK.

## Data we store on your device

- **Injections**: date, medication, dose, injection site, notes
- **Weight entries**: date and value
- **AI coach chats**: your prompts and the model's answers
- **Settings**: schedule, stock, language, theme preferences

All of this can be erased with "Clear data" in Android settings or by uninstalling.

## Data collected by advertising

The App shows ads via Google AdMob. When ads are shown, Google and its partners may
collect and process: device identifiers (including the advertising ID), approximate
location (country/time zone), device and network information, and interaction data, to
serve and measure ads.

Where required by law (EEA, UK, and regulated US states), we obtain your consent
through Google's User Messaging Platform before serving personalized ads. You can
change your choices anytime under **Settings → Privacy options**, or opt out of ads
personalization in Android **Settings → Privacy → Ads**.

## Feedback reports (Support & Feedback)

The App's "Report a Problem" feature lets you send bug reports to our GitHub
issue tracker (`github.com/chartmann1590/dosely`). When you submit a report,
the following information is transmitted to and stored in that repository:

- **Report content**: the title and description you type, plus your name and
  email address **only if you voluntarily enter them** (both fields are
  optional).
- **Optional diagnostics** (on by default; you can disable the toggle in the
  report form): app version, device model and manufacturer, Android version,
  locale, time zone, free/total storage and memory, and a timestamp. No
  contacts, location, credentials, files, or health data are ever included.
- **Optional screenshot**: if you attach one, it is re-encoded on your device
  (stripping EXIF metadata) and stored in the repository as an image
  attachment.

Reports are transmitted through our Cloudflare Worker proxy, which adds
authentication and does not store report data. The recipient is the Dosely
GitHub repository owned by Charles Hartmann.

**Visibility**: if the repository is public, your report — including any name,
email, screenshot, and diagnostics — is publicly visible. The report form
warns you about this before submission. Please do not include personal,
medical, or other sensitive information in reports.

**Retention and deletion**: reports are kept in the issue tracker until we
delete them. You may request deletion of a report (including attached
screenshots and any personal information) by contacting the developer. Note
that image attachments can remain in the repository's git history after
deletion unless the history is rewritten.

## Data safety summary (Google Play)

- **Health data (injections, weight)**: collected? No. Shared? No. Stored on device only.
- **Personal info (name, email)**: collected? Yes — only if you type them into
  the optional feedback form. Shared with GitHub as part of the report.
  Encrypted in transit: yes. Deletion: request removal from the issue tracker.
- **App info and performance (feedback diagnostics)**: collected? Yes — only
  when you submit a feedback report with the diagnostics toggle enabled.
  Shared with GitHub as part of the report. Deletion: request removal.
- **Photos (feedback screenshots)**: collected? Only images you explicitly
  attach to a feedback report. Shared with GitHub as part of the report.
  Deletion: request removal.
- **Device/other IDs (advertising ID)**: collected by the ad provider when ads are
  shown. Shared with Google for ad serving. Encrypted in transit: yes. Deletion:
  remove ads data via Android ad settings or uninstall.
- **App activity (AI chat)**: stored on device only; the "report" action removes the
  message locally and does not transmit it.

## Children

The App is not directed to children under 13 and is intended for adults managing
prescription medication. We do not knowingly collect data from children.

## Permissions

- `POST_NOTIFICATIONS`: to deliver injection, refill and weigh-in reminders.
- `INTERNET` / `AD_ID`: to download the optional AI model and language packs, and to
  show ads.

No location, contacts, camera, microphone, or health-store permissions are used.

## AI features

The AI coach runs a language model locally on your device. Prompts and responses never
leave your device. AI output is labeled as AI-generated and can be reported and removed
in-app. It is not medical advice.

## Changes to this policy

We will update this policy when features or data practices change, with a new "last
updated" date.

## Contact

Privacy questions: **me@charleshartmann.com**
