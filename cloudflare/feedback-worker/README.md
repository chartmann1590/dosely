# Dosely Feedback Worker

Cloudflare Worker that backs the in-app **Support & Feedback** feature. The
Android app talks **only** to this worker — never to GitHub directly — and this
worker is the single place the GitHub credential exists at runtime.

## Architecture

```text
Android app  --HTTPS/JSON-->  Cloudflare Worker  --GitHub REST API-->  GitHub repository
                                                  Bearer env.GITHUB_TOKEN   Issues / Comments / feedback-assets/
```

The Android client is untrusted: it cannot choose the GitHub host, repository,
path, or credentials. Those are fixed by this worker's configuration and secret.

## Routes

| Method | Path                          | Purpose                        |
|--------|-------------------------------|--------------------------------|
| GET    | `/health`                     | Liveness + repo configuration check |
| POST   | `/api/issues`                 | Create a feedback issue        |
| POST   | `/api/issues-with-asset`      | Create an issue + attachment atomically |
| GET    | `/api/issues/:number`         | Fetch issue state              |
| GET    | `/api/issues/:number/comments`| List comments (oldest first)   |
| POST   | `/api/issues/:number/comments`| Post a reply                   |
| POST   | `/api/issues/:number/comments-with-asset` | Post a reply + attachment atomically |
| POST   | `/api/assets`                 | Upload a screenshot/image      |

## Configuration

Non-secret values live in `wrangler.jsonc`:

- `GITHUB_REPO_OWNER`
- `GITHUB_REPO_NAME`
- `FEEDBACK_ASSETS_DIR`

The GitHub token is a **Cloudflare Worker secret** named `GITHUB_TOKEN`:

```bash
printf '%s' '<token>' | npx wrangler secret put GITHUB_TOKEN
```

It is never stored in this repository, never logged, and never returned by any
endpoint.

A second optional secret, `FEEDBACK_WORKER_API_KEY`, turns on a shared-key
gate: when set, every `/api` request must carry it in the `X-Api-Key` header
(`/health` stays open). The app embeds the same value via the
`FEEDBACK_API_KEY` gradle property / env var. This is defense in depth on top
of rate limiting and payload validation — the GitHub token itself is never
embedded in the app and never leaves Cloudflare.

### Idempotency

Write endpoints honor an `X-Idempotency-Key` header carrying a client-generated
UUID v4. The key is embedded in the created issue/comment body as an HTML
comment and checked against recently created items, so a client retry after an
ambiguous failure (timeout, 502, dropped connection) returns the original item
instead of duplicating it. Dedupe scans are bounded to a recent window and a
few pages; edited submissions carry a fresh key and are never swallowed.

## Validation & abuse protection

- Fixed repository; no generic GitHub proxying.
- Optional shared API key gate (`X-Api-Key` / `FEEDBACK_WORKER_API_KEY` secret).
- Strict route/method/Content-Type validation.
- Issue titles are force-prefixed with the `[Feedback]` marker server-side;
  reads and writes only ever touch marker-namespace issues.
- Issue title ≤ 200 chars; issue body ≤ 50 KB; comment ≤ 25 KB.
- Images: Base64 ≤ ~11 MB (~8 MB decoded), extensions limited to png/jpg/jpeg/webp,
  full structural validation with chunk/segment allowlists (no tEXt/COM/APPn
  smuggling, no metadata sidecars, image data required).
- Filename sanitization: path traversal stripped; uploads always land under `feedback-assets/`.
- Issue numbers validated as positive integers.
- Per-IP rate limiting: 30 POST / 60 GET requests per hour (best-effort, per isolate).
- Idempotency keys on all write endpoints (see above).

## Android configuration

The app reads the (non-secret) worker URL from `BuildConfig.FEEDBACK_WORKER_URL`,
overridable via `-Pfeedback.worker.url=...` or the `FEEDBACK_WORKER_URL` env var.
If it is missing, the feedback UI disables submission and shows a configuration
error instead of crashing.

## Development

```bash
npm install
npm run typecheck
npm test
npm run deploy   # wrangler deploy
```
