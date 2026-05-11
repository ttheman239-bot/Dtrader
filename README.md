# Dtrader

Android companion app for the **AI + Power + Infra** day-trading workflow.

The app is a session-aware day-trade cockpit. Thai + English UI.

1. **Master Plan (เกมเช้านี้)** — institutional 7-step framework:
   regime classifier (10 buckets: AI Acceleration / AI Consolidation / Power
   Bottleneck / Broad Momentum / Selective Rotation / Defensive Flow /
   Crowded Euphoric / Fragile / Liquidity Squeeze / Risk-Off), futures &
   yields read, strongest & weakest sectors with 5-day flow context,
   per-stock institutional read (narrative, RS, liquidity, catalyst,
   sustainability, crowding), narrative rotation arrow (e.g. *Semis → Power*)
   with stage + driver classification, expectation analysis (crowded vs
   under-owned), opening watchlist (max 3) with confirms / invalidates /
   institutional signature / entry style, avoid list, and final-day-type
   conclusion. Auto-refresh every 30s.
2. **Flow (เงินไหล)** — pure real-time data: regime read, benchmarks, sector ETFs sorted by intraday %, watchlist ranked by RS vs SPY. Auto-refresh every 30s.
3. **Pre-Market (เตรียมตัว)** — futures + yields, sector flow, catalysts, premarket movers, 6-step checklist.
4. **Execute (เข้าออเดอร์)** — VWAP-reclaim setup, AI-Infra-Day & Power-Rotation playbooks, hard rules.
5. **Tools (เครื่องมือ)** — TradingView, Finviz, Benzinga, TrendForce, Earnings Whispers, FOMC calendar, macro quick look.

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
**every push to any branch** (and on manual dispatch), then publishes a
new GitHub Release with the APK attached. Markdown / `.gitignore` / `LICENSE`
changes are skipped so doc edits don't burn build minutes.
