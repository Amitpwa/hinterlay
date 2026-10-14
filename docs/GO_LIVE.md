# Hinterlay: go-live guide (from this folder to the Play Store)

Follow the steps in order. Each step says exactly where to click and which file to edit.
Plan for about half a day of setup, then Google's review (new apps usually 1 to 7 days).

## 0. What Firebase is used for (and what it is not)

Firebase is **not** only for referrals. In this app it powers:

| Feature | Needs Firebase? |
|---|---|
| Push notifications (your updates and engagement messages) | Yes (Cloud Messaging) |
| Inspire tab and live cards on the home screen | Yes (Firestore). Built-in samples show until you publish your own |
| Owner dashboard (installs, active users, retention, feature use) | Yes (Firestore + Analytics) |
| Referral "share to earn an ad-free session" | **No.** It is local-only today (once per 24h, honour system). A verified referral needs its own backend; the hook for it is `ReferralBackend` in `app/.../data/Rewards.kt` |
| Everything else (editing, stickers, wallpaper, ads, purchases) | No |

The app builds and runs **without** Firebase. Cloud features switch on the moment you add the config file in step 1.

## 1. Firebase (about 30 minutes)

1. Go to https://console.firebase.google.com and click **Add project**. Name it Hinterlay.
2. **Add the Android apps** (Project settings, Your apps, Android icon). Add two apps:
   - `com.hinterlay.app` (release)
   - `com.hinterlay.app.debug` (debug builds)
3. Download `google-services.json` and put it here: **`app/google-services.json`**.
   This is the one place the app credentials go. Do not commit it if the repo is public.
4. In the console turn on:
   - **Authentication**: Anonymous and Google sign-in providers.
   - **Firestore Database**: create in production mode.
   - **Storage**: for Inspire and card images.
   - **Cloud Messaging**: on by default.
5. **Admin panel credentials** (this is the second place):
   - Project settings, General, Your apps, **Add app, Web**. Copy the `firebaseConfig` values.
   - Paste them into `admin/app.js` replacing every `REPLACE_ME`.
   - Set `OWNER_EMAIL` to your Google account in **four** files: `admin/app.js`, `admin/firestore.rules`, `admin/storage.rules`, `admin/functions/index.js`.
   - Put your project id in `admin/.firebaserc`.
6. Install the Firebase CLI once: `npm install -g firebase-tools`, then:
   ```
   cd admin
   firebase login
   firebase deploy --only firestore:rules,storage,hosting
   ```
   Your console is then live at `https://<project-id>.web.app`.
   To try it locally first, double-click `admin/start-admin.cmd` (and add `?demo=1` to the address for sample data).
7. Optional: Cloud Functions for the push form in the console need the Blaze plan.
   `cd admin/functions && npm install`, then `firebase deploy --only functions`.
   Without it, send pushes from Firebase Console, Messaging, new campaign, target topic `all`.
8. Rebuild the app. Check it worked: open the app, then look at Firestore: a `users` document and a `stats` document appear.

Details and screenshots-free troubleshooting: `docs/SETUP_FIREBASE.md`. KPI definitions: `docs/KPIs.md`.

## 2. AdMob (about 20 minutes)

1. https://admob.google.com, **Apps, Add app**, Android, not yet published.
2. Create 3 ad units: **Banner**, **Interstitial**, **Rewarded**.
3. Put the ids in `gradle.properties` (better: `%USERPROFILE%\.gradle\gradle.properties` so they stay out of git):
   ```
   ADMOB_APP_ID=ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY
   ADMOB_BANNER_ID=ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ
   ADMOB_INTERSTITIAL_ID=...
   ADMOB_REWARDED_ID=...
   ```
   Debug builds always use Google's test ads. Release builds use your ids. **Never click your own live ads.**
4. AdMob, **Privacy & messaging**: create the **GDPR** message and the **US states** message and publish them.
5. Host `app-ads.txt` at the root of the website you list on Play (template: `app-ads.txt`; replace the publisher id).

Ad behaviour today: a rewarded ad when the user taps Export (clearly labelled), a banner beside the export progress. The start-of-session interstitial was removed to keep the app fast and smooth; `AdsManager.showStartInterstitial` still exists if you want it back.

## 3. Play Billing (about 15 minutes)

In Play Console, your app, **Monetize, In-app products**, create four products with these exact ids and make them **Active**:

| Product id | Type | Price |
|---|---|---|
| `tip_small` | consumable | e.g. $1 |
| `tip_medium` | consumable | e.g. $5 |
| `tip_large` | consumable | e.g. $20 |
| `lifetime_ad_free` | one-time (non-consumable) | $100 |

Product setup only works after you upload a build (step 5). Test with a licence-tester account (Settings, License testing).

## 4. Signing and building the release

The signing key already exists: `hinterlay-release.jks` with its passwords in `keystore.properties` (both git-ignored).

**Back both up now** (password manager plus an offline copy). If you lose them you cannot update the app. Recommended: enrol in **Play App Signing** when you create the app, so Google holds the real key and this one is just an upload key.

Build:
```
.\gradlew.bat bundleRelease      # for the Play Store: app\build\outputs\bundle\release\app-release.aab
.\gradlew.bat assembleRelease    # for installing on your phone: app\build\outputs\apk\release\app-release.apk
```
Before every update raise `versionCode` (and `versionName`) in `app/build.gradle.kts`.

## 5. Play Console (about 2 hours)

1. https://play.google.com/console, **Create app**. Pay the one-time registration fee if you have not.
2. **Store listing**: name, short and full description, **512x512 icon**, **1024x500 feature graphic**, at least 2 phone screenshots (take real ones from the app).
3. **Privacy policy URL**: upload the contents of the `privacy-site/` folder (`index.md`, `_config.yml`) to a public GitHub repo, turn on Pages (Settings, Pages, branch `main`, root) and paste the `https://<user>.github.io/<repo>/` link. The policy is already filled in with the publisher and contact details. The app also shows the policy inside More, Privacy policy.
4. **App content** (Policy, App content):
   - Ads: **Yes**.
   - Target audience: 13+ (do not pick under-13: that triggers the Families policy).
   - Content rating questionnaire: answer honestly (expect Everyone).
   - **Data safety**: declare
     - Device or other IDs (advertising id, anonymous install id): collected, shared with Google (ads, analytics).
     - App interactions: collected (anonymous usage counts).
     - Approximate location from IP (AdMob): collected.
     - Crash logs and diagnostics: collected.
     - Purchase history: collected by Google Play.
     - Photos: **not collected** (processed on device).
     - Camera: used only to take photos you edit, nothing uploaded. Approximate location: only used on demand to print a place name on a photo (declare as collected = No if you never send it to your servers; the Android geocoder is a system service).
     - Data encrypted in transit: yes. Deletion: users can turn off usage data in More and clear app data.
   - Advertising ID declaration: yes.
   - Permissions: `INTERNET`, network state, `POST_NOTIFICATIONS` (asked after the first export), `CAMERA` (asked when the camera opens), `ACCESS_COARSE_LOCATION` (asked when the user taps the Place stamp) and the ad id. There is no storage or media permission. Mention in the listing that the camera and location are optional.
5. **Release**: Testing, **Internal testing**, create a release, upload the `.aab`, add yourself as tester, install from the Play link on your phone.
6. Test everything on that Play-installed build: onboarding, photo picking, erase, perspective, export (rewarded ad), stickers, wallpaper, a tip purchase with a licence tester, push from the console.
7. Personal developer accounts created after Nov 2023 must run a **closed test with 12+ testers for 14 days** before production access. Start that early: Testing, Closed testing, add testers by email, keep them opted in for 14 days.
8. Then **Production**, create release, roll out (start with 20 percent if you want a safety net).

## 6. Final pre-launch checklist

- [ ] `app/google-services.json` present, a `users` document appears in Firestore after launching a release build
- [ ] Real AdMob ids set; test ad vs live ad confirmed (debug = test, release = live)
- [ ] Billing products active, one tip purchase tested
- [ ] Privacy policy placeholders filled and hosted; Data safety form matches
- [ ] Keystore and `keystore.properties` backed up
- [ ] `versionCode` raised
- [ ] Optional: replace the rendered onboarding scenes with real photos (see below)
- [ ] Optional: add Crashlytics for crash reports (add the dependency and plugin, then rebuild)

## 7. After launch

- Watch `docs/KPIs.md` weekly: installs, DAU/MAU, D1/D7 retention, exports per user, feature use.
- Publish 2 to 3 **Inspire** items a week and one **live card** per feature release. Send at most 1 to 2 pushes a week.
- Uninstalls and store conversion: Firebase Analytics (`app_remove`) and Play Console.

## Optional: real photos in the onboarding

The onboarding and home hero are rendered in code (a layered dusk landscape). To use real photos instead,
drop four images named `onboard_1.jpg` to `onboard_4.jpg` (about 1080x1180 px, JPEG, under 300 KB each)
into `app/src/main/res/drawable-nodpi/`. The app picks them up automatically with a slow zoom.
Use photos you own or that are licensed for commercial use (for example Unsplash or Pexels licence).

## Shortcuts, widgets and Google Assistant

- Long-press the app icon: Camera, Pick photo, Library, Stickers (res/xml/shortcuts.xml).
- Widgets: add **Hinterlay Quick actions** (4x1) or **Latest creation** (2x2) from the launcher's widget picker. On phones that allow lock-screen widgets they can sit there too.
- Quick Settings tile: edit the tile panel and add **Quick camera**. It also works from the lock screen's quick panel.
- The camera opened by the tile or the widget shows over the lock screen. It only holds the camera; opening a photo for editing asks for the usual unlock first.
- Google Assistant: the four shortcuts carry App Actions bindings (OPEN_APP_FEATURE). Assistant picks them up only after the app is published on Google Play with the same package name; test with the Google Assistant plugin for Android Studio or the App Actions Test Tool.

## Firebase live credentials, Google Analytics, Microsoft Clarity, PayPal

**Firebase live credentials (the app and Google Analytics).** The code is wired; it switches on when the file exists.
1. Firebase console > Project settings > Your apps > add the Android app `com.hinterlay.app` (and `com.hinterlay.app.debug` for debug builds).
2. Download **google-services.json** and put it at **`app/google-services.json`**. Rebuild.
3. Google Analytics is Firebase Analytics (GA4): it is enabled with the project. You will see screens (`screen_view`), and events such as `photo_selected`, `export_completed`, `magic_applied`, `crop_used`, `sticker_created`, `purchase_completed`. Mark the important ones as conversions in Analytics > Events. Add your SHA-1 and SHA-256 (the upload key from `hinterlay-release.jks` and the Play App Signing key from Play Console > App integrity) under Project settings so sign-in and links work.
4. Debug a build with `adb shell setprop debug.firebase.analytics.app com.hinterlay.app.debug` and open Analytics > DebugView.

**Microsoft Clarity.**
1. clarity.microsoft.com > New project > Android app. Copy the **project id**.
2. Put it in `gradle.properties` as `CLARITY_PROJECT_ID=...` and rebuild. Blank = Clarity off.
3. The app pauses Clarity on the editor, crop and camera screens, so photos are never recorded. Keep it that way: do not add Clarity to those screens, and describe it in your privacy policy and Data safety form (app interactions and diagnostics, shared with Microsoft).
4. Replays and heatmaps appear in the Clarity dashboard after a few sessions.

**PayPal donations.** Set `PAYPAL_DONATE_URL=https://www.paypal.me/yourname` in `gradle.properties`; blank hides the button. Important:
- Google Play requires that anything digital the user gets (including removing ads) is sold through Google Play Billing. So ad-free stays on Play Billing only, and PayPal is a plain donation that unlocks nothing.
- Even so, Play's payments policy only clearly allows external donations for registered non-profits. A donation link for an individual developer can be flagged during review. The safe choices are: leave `PAYPAL_DONATE_URL` blank in the Play build and use it only in a website or sideloaded build, or accept the risk of a policy rejection.
- Declare it truthfully in Play Console if you keep it.