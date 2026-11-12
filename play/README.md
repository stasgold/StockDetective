# Google Play submission kit

Everything needed to publish Stocks Detective (`com.stocksdetective.app`) on Google Play. Assets are in this folder.

## 1. One-time: create the upload key

Google Play signs the app for users ("Play App Signing"). You sign uploads with your own **upload key**. Create it once, outside the repo, and back it up. If you lose it, you have to ask Google for an upload key reset.

```sh
mkdir -p ~/keys
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -genkeypair -v \
  -keystore ~/keys/stocksdetective-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` in the project root. It is git-ignored; never commit it.

```properties
storeFile=/Users/<you>/keys/stocksdetective-upload.jks
storePassword=<keystore password>
keyAlias=upload
keyPassword=<key password>
```

## 2. Build the App Bundle

```sh
./gradlew :app:bundleRelease -PversionCode=1 -PversionName=1.0
# → app/build/outputs/bundle/release/app-release.aab
```

Every later upload needs a higher `versionCode` (2, 3, …). To sideload-test the minified build first, run `./gradlew :app:installRelease -PversionCode=1`. It's R8-minified, unlike the debug build, so check that Settings, a case file and Interrogate all work.

CI: the GitHub workflow now also builds `app-release.aab` (versionCode = run number). Add the four `ANDROID_*` secrets from the README so CI uploads are signed with the same upload key.

## 3. Play Console setup

1. **Create app**: name *Stocks Detective*, App (not game), Free, default language English (US).
2. **Testing requirement:** a *personal* developer account created after Nov 13, 2023 must run a **closed test with ≥12 testers opted in for 14 continuous days** before it can apply for production. Recruit 15–20 testers to allow for drop-outs. Organization accounts are exempt.
3. **Release**: Testing › Closed testing › create a track › upload the `.aab` › accept **Play App Signing**.

## 4. Store listing

**App name** (≤30): `Stocks Detective`

**Short description** (≤80):
> Why your stocks moved: the news behind each move, a verdict, and what's next.

**Full description** (≤4000):
> Most apps tell you "AAPL is down 3%." Stocks Detective tells you **why**.
>
> For every unusual move in your portfolio, the detective opens a case:
>
> 🔎 **What happened**: the move in plain English, with the numbers that matter.
> 📰 **Which news caused it**: the headlines that best explain the move, with sources.
> ⚖️ **Is it justified?**: a verdict: Justified, Overreaction, Underreaction, Unexplained or Just noise.
> 👀 **What to watch next**: specific, checkable things for the coming days.
>
> **Daily briefing:** one read on your whole portfolio: what moved, why, and what matters today.
>
> **Interrogate the detective:** ask questions in plain language ("Why did NVDA jump?", "Is my tech exposure risky?"). It looks up prices and headlines for the tickers you mention.
>
> **Private by design:** no account and no sign-up. Your portfolio stays on your phone. On phones with Gemini Nano, analysis runs on the device. Otherwise a built-in rule-based analyst does the work. You can optionally connect Google's Gemini cloud models with your own free API key from Google AI Studio for deeper analysis.
>
> **Unusual-move alerts:** get notified when a holding makes a stock-specific move, and tap the alert to open the case.
>
> **Easy import:** paste or import your holdings from a broker export.
>
> **Demo mode:** explore every verdict type offline with built-in sample stories.
>
> Prices and headlines come from Yahoo Finance and Google News and may be delayed.
>
> Stocks Detective is for information only and is not investment advice.

**Graphics** (in `play/`):
| Asset | File |
|---|---|
| App icon 512×512 | `icon-512.png` |
| Feature graphic 1024×500 | `feature-graphic-1024x500.png` |
| Phone screenshots (1200×2400, 7) | `screenshots/01…07-*.png` |

The originals in `docs/screenshots` are 1200×2580, which is over Play's 2:1 aspect limit, so these copies are cropped to 1200×2400.

**Category:** Finance · **Tags:** stocks, portfolio, market news
**Contact email:** stasgold@gmail.com
**Privacy policy URL:** `https://github.com/stasgold/StockDetective/blob/main/docs/PRIVACY.md`

## 5. App content (Policy › App content)

| Section | Answer |
|---|---|
| Privacy policy | URL above |
| Ads | No ads |
| App access | All functionality available without special access. Mention: "Optional Gemini cloud needs the user's own Google AI Studio API key. All other features work without it. Demo data: Settings › Demo data." |
| Content rating (IARC questionnaire) | Category *Reference, News, or Educational* / utility; answer No to violence, sexuality, language, controlled substances, gambling; **No** user-to-user interaction; **No** sharing of location. Expected rating: Everyone / PEGI 3 |
| Target audience | 18+ only (finance app, keeps it out of the Families program) |
| News app | No (it summarizes third-party headlines about your holdings; it's not a news publisher) |
| Financial features | Choose the option that matches *stock / portfolio information and tracking, no trading, payments, loans or crypto*. If none fits exactly, select "My app doesn't provide any financial features" only if the form's definitions exclude informational apps. Read the form's descriptions carefully. |
| Government app | No |
| Health | No |
| Data safety | See below |

## 6. Data safety form

The app has no backend. "Collected" in Play's sense means *any data transmitted off the device*, including by SDKs and to third-party APIs.

**Does your app collect or share any of the required user data types?** Yes

**Is all of the user data collected by your app encrypted in transit?** Yes (HTTPS only)

**Do you provide a way for users to request that their data is deleted?** The app keeps no data off-device, so answer per the form's guidance: data is deleted by clearing app storage or uninstalling. Say so in the privacy policy, which it already does.

| Data type | Collected | Shared | Optional? | Purposes | Why |
|---|---|---|---|---|---|
| **Financial info › Other financial info** (portfolio holdings) | Yes | Yes (Google, only with Gemini cloud) | Optional | App functionality | Tickers go to Yahoo/Google News for prices and news. With Gemini cloud on, holdings go to Google's Gemini API |
| **App info and performance › Diagnostics**, **Other app performance data** | Yes | No | Required | Analytics | ML Kit SDK diagnostics (per Google's ML Kit data disclosure) |
| **Device or other IDs** | Yes | No | Required | Analytics | ML Kit per-installation identifiers |

Not collected: location, personal info (name/email), messages, photos, contacts, app activity, web history, health, payment info.

On "Shared": Play exempts transfers to a *service provider* processing data on your behalf and transfers the user explicitly initiates. Ticker lookups to Yahoo/News are arguably a necessary part of the feature, and Gemini cloud is a user-initiated opt-in under the user's own key. The table above takes the conservative route of declaring Gemini as sharing. You're responsible for the final answers.

## 7. Risks to review before submitting

- **Yahoo Finance endpoints are unofficial.** `query1.finance.yahoo.com` isn't a licensed public API, and Yahoo's terms restrict commercial use. That's fine for personal use, but for a public Play listing consider a licensed data provider, or at least make sure the app degrades gracefully if Yahoo blocks requests.
- **Financial-advice wording:** keep "not investment advice" in the listing and the app (both have it). Avoid "buy/sell" language in screenshots.
- **POST_NOTIFICATIONS / background work:** WorkManager every ~30 min is fine. No exact-alarm or foreground-service permission declarations are needed beyond what libraries merge in. If Play flags `FOREGROUND_SERVICE` (merged from WorkManager), the app doesn't start foreground services itself. Answer accordingly or remove the merged permission with `tools:node="remove"`.
- **Target SDK:** 37, which meets Play's requirement (API 36+ for new apps since Aug 31, 2026).
