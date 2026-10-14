# Google Play + AdMob compliance checklist — Hinterlay

## Already handled in code

- [x] **Target API** 36 (meets Play's 2026 target-level requirement for new apps and updates).
- [x] **Photo & Video Permissions policy**: uses the system Photo Picker. No `READ_MEDIA_IMAGES`,
      no `READ_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE`.
- [x] **Saving** via MediaStore to `Pictures/Hinterlay` — no storage permission on Android 10+.
- [x] **Consent**: Google UMP (certified CMP) shown before any ad request; "Privacy & ad choices"
      menu entry appears when UMP says it's required.
- [x] **Ads only after consent** (`canRequestAds()`), SDK initialised off the main thread.
- [x] **No ads at app launch**; interstitial only after a user action, frequency-capped (90 s).
- [x] **Rewarded ad is opt-in** with a clear "Watch ad & export" button and an "Ad" badge.
- [x] **No custom UI over full-screen ads**; the progress banner sits beside content.
- [x] **Never blocks the user** when no ad is available.
- [x] **No artificial delays** — export progress reflects real work.
- [x] **Test ads in debug builds** (prevents invalid-traffic violations from your own clicks).
- [x] **Edge-to-edge** + predictive back (`enableOnBackInvokedCallback`).
- [x] **On-device processing** — photos are not uploaded, so no photo data is "collected".
- [x] Backups exclude app files.

## You must do in the consoles

### AdMob
- [ ] Create the app + 3 ad units; put IDs in `gradle.properties`.
- [ ] Privacy & messaging → create a **GDPR** message and a **US states** message.
- [ ] Publish **app-ads.txt** at the root of the developer website listed on Play
      (template in `app-ads.txt` — replace the publisher ID).
- [ ] Don't click your own live ads. Add your phone as a test device.

### Play Console
- [ ] **Privacy policy URL** (template: `PRIVACY_POLICY.md`) — in the store listing *and* in-app (already linked from the menu).
- [ ] **Ads declaration**: "Yes, my app contains ads".
- [ ] **Advertising ID declaration**: Yes — used for advertising and analytics (permission is merged by the Ads SDK).
- [ ] **Data safety** (per Google's guidance for the Mobile Ads SDK):
  - Collected & shared: *Device or other IDs*; *Approximate location* (derived from IP); *App interactions*; *Crash logs*, *Diagnostics*.
  - Purposes: Advertising or marketing, Analytics, Fraud prevention/security.
  - Encrypted in transit: Yes. Users can request deletion: via Google ad settings / reset Ad ID.
  - Photos: **not collected** (processed on device only).
- [ ] **Target audience**: 13+ or 18+ only. Do **not** select under-13 ages — that brings in the
      Families policy and requires Families-certified ad settings.
- [ ] **Content rating** questionnaire (expect "Everyone"/3+ content but ads present).
- [ ] Store listing screenshots must show the real app; mention that it's ad-supported.

## Things to avoid when you change the ad flow later
- Showing interstitials on every tap or before the app content loads.
- Requiring a rewarded ad without an explicit, labelled opt-in.
- Drawing your own progress bar, buttons or timers on top of an ad.
- Faking a slow export to stretch ad time.
- Ads placed right next to buttons people tap often (accidental clicks).
