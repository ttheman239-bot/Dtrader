# Dtrader

Android companion app for the **AI + Power + Infra** day-trading workflow.

The app is a structured, opinionated playbook built around three phases:

1. **Phase 1 — Pre-Market** — futures + yields, sector flow, catalysts, premarket movers.
2. **Phase 2 — Open** — relative-strength ranking against a curated AI / Power watchlist with one-tap deep links to TradingView and Finviz.
3. **Phase 3 — Execution** — VWAP reclaim setup, scenario playbooks (AI Infra Day, Power Rotation), hard rules.
4. **Tools** — quick-access cheat sheet (TradingView, Finviz, Benzinga, TrendForce, Earnings Whispers, macro quick look).

Built with **Kotlin + Jetpack Compose + Material 3**. Targets **Android 15
(API 35)**, runs on Android 7.0+. Dark, trading-desk inspired theme.

## Install

Download the latest APK from the [Releases](../../releases) page and install
on Android 7.0 (API 24) or later. The APK is signed with the Android debug
key — to install, allow "Install from unknown sources" for the installer app.

## Build locally

```bash
./gradlew assembleRelease
```

APK output: `app/build/outputs/apk/release/app-release.apk`

## CI / Releases

The `Android Release APK` GitHub Actions workflow builds a release APK on
every push to `claude/build-android-apk-release-*` and on manual dispatch,
then publishes a GitHub Release with the APK attached.
