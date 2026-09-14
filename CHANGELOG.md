# Changelog

## 1.1.1

- Retry hook installation up to six times on the Telegram UI thread, with cancellation on unload.

## 1.1.0

- Moved the DEX namespace to `app.soranerai.continuousanimations`.
- Reworked hook lifecycle and isolated version-dependent Telegram calls.
- Added reproducible build, verification, and GitHub Actions pipeline.
