# Stocks Detective: Privacy Policy

_Last updated: November 12, 2026_

Stocks Detective ("the app") is an independent app made by Stas Goldberg ("I", "me"). This policy explains what happens to your information when you use the Android app.

**Summary:** I don't run any servers and I don't collect your data. Your portfolio stays on your phone. To show prices and news, the app asks public data services about the ticker symbols you follow. If you turn on Gemini cloud, the information needed for an analysis is sent to Google under your own API key.

## Data stored on your device

- **Your portfolio:** the ticker symbols and share counts you enter or import, and your settings. These are stored only in the app's private storage on your phone. They may be included in your phone's Android backup, which is governed by Google's backup terms.
- **Your Gemini API key**, if you enter one. It is stored in a separate private file that is **excluded** from Android backups and device-to-device transfers.

You can delete this data at any time by removing holdings in the app, clearing the app's storage, or uninstalling the app.

## Data sent to third-party services

The app connects directly from your phone to these services. I don't receive or see any of this traffic.

| Service | What is sent | Why |
|---|---|---|
| Yahoo Finance (query1.finance.yahoo.com, feeds.finance.yahoo.com) | Ticker symbols you follow or search for | Prices, charts, company names and headlines |
| Google News RSS (news.google.com) | Ticker symbols / company names | Headlines that explain price moves |
| Google Gemini API (generativelanguage.googleapis.com): **only if you turn on Gemini cloud and enter your own key** | Your API key, plus the analysis request: holdings (tickers and position details), price data and headlines | AI-written analysis |
| Google ML Kit (on-device Gemini Nano, on supported phones) | The analysis runs on your device. The ML Kit library sends Google diagnostic and usage data (device model, OS version, app package/version, per-installation identifiers, performance metrics). It does not send your portfolio. | Running and diagnosing the on-device model |

Each service handles the data it receives under its own privacy policy:
- Yahoo: https://legal.yahoo.com/us/en/yahoo/privacy/index.html
- Google (News, Gemini API, ML Kit): https://policies.google.com/privacy and https://ai.google.dev/gemini-api/terms

All connections use HTTPS.

## What I don't do

- No accounts, sign-in, advertising, ad identifiers, or analytics of my own.
- No selling or sharing of your data. I never receive your data in the first place.
- No location, contacts, photos, or other personal data is accessed.

## Notifications

If you allow notifications, the app checks your holdings in the background about every 30 minutes and alerts you about unusual moves. This uses the same data services listed above.

## Children

The app is not directed to children under 13 and is intended for adults.

## Not investment advice

The app's analyses are informational and are not investment advice.

## Changes

If this policy changes, I'll update this page and the date above.

## Contact

Questions: stasgold@gmail.com
