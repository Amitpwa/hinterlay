# Hinterlay: text behind anything

Android photo editor that finds the subject in a photo (on-device) and lets you place
text **behind** it, then export in full quality, as a Story, as WhatsApp stickers or as a
3D tilt wallpaper. Free, supported by AdMob, with optional tips and a lifetime ad-free purchase.

**Ready to publish? Start with [`docs/GO_LIVE.md`](docs/GO_LIVE.md).**

## Features

- Crop (free and 1:1, 4:5, 9:16, 16:9, 3:4) and Smooth (skin-only softening) buttons on the canvas itself, so they are always visible
- Home screen demo uses real before/after photos (app/src/main/res/drawable-nodpi/hero_before.jpg and hero_after.jpg): swap them for your own
- **Do magic**: reads the scene on-device and offers 3 complete looks (words, typeface, colour, placement); More ideas gives a fresh set
- Home-screen widgets (quick actions, latest creation), long-press launcher shortcuts, a Quick Settings tile, and a camera that opens over the lock screen
- Floating bottom bar with a raised camera button: live camera with colour filters and funny face masks (shades, hearts, puppy, kitty, clown, crown, alien), straight into the editor
- Photo adjustments (looks, brightness, contrast, saturation, warmth, vignette), one-tap stamps for day, date, time, your place, and emoji
- Resizable editor panel: drag the handle up or down, or tap it to hide the controls
- Subject detection on-device (ML Kit), text between background and subject, per-layer erase, subject erase
- Google Fonts (bundled), bubble text, shadows (strength and colour), letter case, perspective warp
- On-device AI ideas: caption suggestions and colours taken from the photo
- WhatsApp sticker packs, 3D tilt live wallpaper (the subject never shows twice)
- History kept on the device only
- Inspire tab and live home cards from your server, push notifications, owner dashboard (Firebase, optional)
- Material 3 with Material You colours, sound effects and haptics (can be turned off)
- Play Billing tips and a lifetime ad-free purchase; local "share to earn an ad-free session"

## Run it

1. Open this folder in Android Studio (latest stable) and let Gradle sync.
2. Run the `app` configuration on a device or emulator with Google Play services.
   Debug builds use Google's **test** ads and work without Firebase.
3. Command line: `.\gradlew.bat assembleDebug` (debug APK) or `.\gradlew.bat assembleRelease` (signed release APK).

## Configuration files

| What | Where |
|---|---|
| Firebase app credentials | `app/google-services.json` (see `docs/GO_LIVE.md`, step 1) |
| Firebase admin panel credentials and owner email | `admin/app.js` and three more files, listed in the guide |
| AdMob ids | `gradle.properties` (`ADMOB_*`) |
| Play Billing products | Play Console: `tip_small`, `tip_medium`, `tip_large`, `lifetime_ad_free` |
| Release signing | `keystore.properties` and `hinterlay-release.jks` (git-ignored; back them up) |

## Code map

```
app/src/main/java/com/hinterlay/app/
├── MainActivity.kt        navigation (Create, Inspire, Library, More), deep links from push
├── ads/                   consent (UMP), rewarded/banner ads
├── ai/                    on-device caption and colour suggestions
├── billing/               Play Billing (tips, lifetime)
├── cloud/                 Firebase: analytics, push, Inspire and live cards (all optional)
├── data/                  history storage, ad-free credits, referral hook
├── editor/                text layer model, EditorViewModel (layers, erase, export pipeline)
├── fx/                    sound effects and haptics
├── render/                Compositor (one renderer for preview and export), subject detection and clean-background fill
├── sticker/               WhatsApp sticker pack and content provider
├── ui/                    theme, components, screens (home, editor, onboarding, library, more)
└── wallpaper/             3D tilt live wallpaper
admin/                     owner-only web console (dashboard, Inspire, cards, push) and Firebase rules
docs/                      GO_LIVE.md, SETUP_FIREBASE.md, KPIs.md
```

Render order: photo, then text marked *Behind subject*, then the subject cut-out, then text in front.
Erased parts of the subject are covered by a soft clean-background fill, so they truly disappear.

## Known limits

- Segmentation gives one subject mask, not a depth map.
- Referral rewards are not verified server-side.
- Not localised yet (English strings are inline).
- No undo/redo for text edits (erase strokes have undo).
