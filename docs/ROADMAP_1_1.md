# Hinterlay 1.1: editing tools and effects roadmap

Status: planning started 2026-10-11. Version 1.0.1 (versionCode 2) is the build going to Play first; copies of that bundle, APK and key files are in `releases/1.0.1/` (git-ignored). Do not build 1.1 on top of those files; bump `versionCode` to 3 for 1.1.

Inspiration: the user's reference video (a video-editor demo: tilt-reactive chrome and holographic materials, glitch, frost wipe, page-curl delete) and the tool set of Snapseed-style editors. Everything here is written from scratch in Hinterlay's own black-and-white minimal style: no copied code, artwork, names or layouts.

## Architecture

- `render/PhotoFx.kt`: pixel pipeline on the photo (CPU, parallel rows). Input: the original photo plus `FxParams`; output: edited photo at the same size. The subject cut-out gets the same edit by masking the edited photo with the cut-out's alpha, so the subject always matches the background.
- `EditorViewModel`: `fx` in state, debounced recompute (latest wins), `fxSource` / `fxForeground` used by the preview and by export. `source` stays the untouched original (history stores the original plus the settings).
- Colour-matrix settings stay in `PhotoAdjust`; everything that needs neighbouring pixels (details, curves, glow, HDR, grain, selective, healing, blur) lives in `FxParams`.
- UI: no new bottom tabs (user rule). A **Tools** pill on the canvas opens a compact panel over the lower part of the photo with a tool strip and that tool's sliders.
- Both are saved with a creation (`meta.json`, keys optional so older creations still open).

## Phases

1. **Core tools** (first release of 1.1): Tune (ambiance, highlights, shadows next to the existing brightness/contrast/saturation), White balance, Details (sharpen, structure), Curves, Glow, HDR, Grain, more Looks.
2. **Local edits**: Selective (control points), Brush (paint an adjustment), Healing (remove small blemishes/objects, reusing the SubjectPlate diffusion fill), Lens blur, Double exposure, Frames.
3. **Text materials**: chrome, holographic, glass and gold text that react to phone tilt in the editor (light angle slider for export). Needs a fallback below Android 13 (no AGSL).
4. **Effects**: glitch (slices + colour split), frost wipe-to-reveal (finger scratches a frosted layer), page-curl delete on Library cards and similar quiet motion.

Release order: 1.0.1 to testers first, then these as updates. Each phase is built, tested on the emulator and a real phone, and only then added to a release.

## Rules to keep

- Black-and-white minimal UI, no purple, no hard shadows, subtle motion and sound.
- Photos never leave the device; Clarity stays paused on editor/crop/camera screens.
- Keep export and preview identical (single `Compositor`).

## Progress (2026-10-11, all checked on the emulator only, none on a phone yet)

Done in the editor: Tools panel (Tune, White balance, Details, Curves, Selective, Brush, Heal, Looks, Glow, HDR, Grain, Vignette), photo tools saved with a creation and applied on export, and a three-way close dialog (Keep editing / Save to Library / Discard; no question when nothing changed).

## Added scope (user, 2026-10-11)

References: an Instagram text-styler (3D balloon letters, style presets Chilly / Fireworks / Balloons / Twinkle / Holiday), a "photo wobble" demo (subject moves against the background like 3D), and the earlier video-editor demo.

1. **3D text materials** (replaces the old "tilt-reactive chrome" item). Built from scratch, no AI service: letters are inflated into a height field (distance transform of the text mask), normals are derived from it, then shaded with an environment reflection, specular, rim light and a soft shadow. Presets: silver balloon, gold balloon, pink balloon, chrome, glass, ice, neon, sparkle. A style carousel under the text like the reference. Reflections follow phone tilt in the editor; a light-angle control sets the exported look.
2. **3D photo (wobble)**. Today the tilt wallpaper has two planes (blurred back plane, sharp subject). For good quality: inpainted background behind the subject so edges never tear, soft depth falloff, and a decision on an on-device depth model (for true multi-layer parallax; adds roughly 15 to 30 MB, check licence). Live tilt preview in the editor; export as MP4 and GIF/WebP.
3. **Video export with music**: render the animation to MP4 (MediaCodec + MediaMuxer). Sound: bundled royalty-free tracks (AAC, muxed without re-encoding) and "pick audio from my phone".
4. **Share**: Instagram Story opens the story composer directly once `FACEBOOK_APP_ID` is set in `gradle.properties` (blank today, so it uses the normal share sheet). WhatsApp has no public Status API: the button opens WhatsApp's share screen, where the user picks "My status". Video shares use the same paths.
5. **Spotify**: not planned. Spotify's developer terms do not allow its music to be used in exported video (verify at developer.spotify.com/policy before deciding), and its SDK only controls playback inside the Spotify app; it gives no audio to mix. Use bundled royalty-free music and the user's own audio file instead. Instagram's own music sticker is available after sharing.
6. **Futuristic look across the app**: keep black-and-white minimal; add one motion language (spring-based, 150 to 250 ms), glassy thin-border panels, consistent haptics and sound, better empty states, tool previews. Done as a dedicated pass after the features, not screen by screen.

Order: 1.0.1 to Play first. 1.1 = editing tools (built). 1.2 = 3D text + 3D photo + video export + share. 1.3 = music and the design pass.

- 3D text materials built 2026-10-11 (Effects tab, 3D look strip: Silver/Gold/Rose balloon, Chrome, Ice, Holo; Light slider; tilt-reactive in the editor; saved with the creation). Checked on the emulator only: look, save and reopen. Not checked: export file, tilt on a real phone, speed on a phone.

- 3D photo (wobble) built 2026-10-11: canvas button 3D photo; layers = background with the subject area filled in, text behind the subject, subject plus front text; styles Wobble / Orbit / Push / Tilt (phone sensor); Depth slider; Save as video (H.264 MP4, 720x1280, 30 fps, 4.5 s loop, saved to Movies/Hinterlay, Share button). Emulator-checked: preview, motion, the saved file's format and frames. Not checked: playing it in a phone gallery, real-phone encoder, Tilt on a phone. No depth model yet and no music yet.

- Futuristic design pass, step 1 (2026-10-11): shared design system. ui/theme/Motion.kt (spring press, settle, enter/exit timings, pressScale, glass panel), ui/components/Components.kt (pill buttons with press feedback, Chip replacing FilterChip everywhere, HairlineSlider, outlined switches), lighter tracked typography, glass Tools panel and canvas pills, screen transitions on the shared timings. Checked on the emulator in dark and light: editor, Tools, Create, Inspire, More. NOT yet restyled/checked: Library, camera, onboarding, stickers, support, dialogs and sheets (export, close, Do magic), 3D photo screen, haptic/sound pass, empty states, tool previews.

- Design pass step 2 (2026-10-11): themed AlertDialog wrapper (ui/components/Components.kt, same call shape, glass card + hairline), hairline edge on all Cards, bottom sheets (export, Do magic, Inspire) with 28 dp top corners and flat surface, export dialog restyled. Emulator-checked: save/discard dialog, export sheet, Library. NOT checked: camera screen, onboarding, stickers, support, Do magic sheet, Inspire sheet, sticker dialogs, export progress dialog, 3D photo screen; haptic/sound pass, empty states and tool previews still to do.

- Design pass step 3 (2026-10-11): camera (glass hairline chips, tab underline, springy shutter, hairline top buttons), single scrolling button row on the editor canvas (labels shortened to Crop / Smooth / Tools / 3D / Undo), thin Smooth slider. Emulator-checked in dark mode: camera controls (preview was black on the emulator), Support, Stickers, onboarding page 1, editor, Do magic sheet, 3D photo screen. NOT checked: light mode of these, camera preview and capture with the new controls, sticker dialogs, export progress dialog. Still to do: haptic/sound pass, empty states (Library with no creations, Inspire offline), tool previews.

- Design pass step 4 (2026-10-11): reusable EmptyState (ui/components/EmptyState.kt: floating line drawing, title, body, one action); Library uses it with a Pick a photo button (onPick wired in MainActivity). Look previews: Tools > Looks shows the user's own photo with every look applied (LookStrip in PhotoTools.kt; 3D text looks already had previews). Emulator-checked in dark mode: both. NOT checked: light mode, Inspire empty state (it has built-in styles so it cannot be empty today), previews for other tools (Curves has its curve drawing, the rest are sliders), the Pick a photo button's picker flow, a real phone.

- Sound and haptics pass (2026-10-11): fx/Fx.kt now has one vocabulary (tick, toggle on/off, detent, open/close, undo, success, error) with 70 ms merging so a control and its handler never double-fire, haptics obey the Sound effects switch, new sounds generated by tools/GenSounds.java into res/raw (fx_error, fx_open, fx_close, fx_undo, fx_detent). Buttons, chips, switches, thin sliders (tick every 5 percent, firmer notch at neutral middle, 45 ms throttle), dialogs and sheets give feedback automatically; camera shutter uses the system MediaActionSound. Emulator: no crashes, vibrations confirmed requested via dumpsys vibrator_manager. NOT verified: how any of it sounds or feels. The Moto G85's log shows haptics as ignored_for_settings, i.e. its touch-feedback setting is off.

- Lens blur built 2026-10-11 (Tools > Blur): PhotoFx.render() now produces the background and the matching subject for preview, export, Save to Library and the 3D photo. With a subject the background is blurred from the clean fill (no halo) and the subject stays sharp; without one the edges blur and the middle stays sharp. Saved in meta.json (lensBlur). Emulator-checked: preview, exported file, Brush and Selective still work. NOT checked: a photo with no subject, a phone, very large photos (speed).
- INCIDENT 2026-10-11: render/PhotoFx.kt was wiped to 0 bytes by a failed scripted edit and rebuilt from the code written earlier in the session; it compiles and Structure, Brush, Selective and Blur were re-tested. Source snapshots are now kept in releases/src-snapshots/ (git-ignored). A git repo with commits would be safer: the folder is a repo but nothing is committed.

- Double exposure built 2026-10-11 (Tools > Blend): second photo mixed in with Screen / Mix / Multiply / Overlay / Lighten / Darken, strength, zoom, move, flip, and where it shows (everywhere / in subject / background). Saved with the creation (blend.jpg + meta.json). Emulator-checked: everywhere, in subject, background, save, reopen with settings. NOT checked: export file with a blend, 3D photo with a blend, a photo with no subject, a phone.
- Erase strength + overlap-only built 2026-10-11 (Erase tab): Strength slider (5 to 100 percent) so erased text or subject stays partly see-through, and a switch "Only where text and subject overlap" (works for both targets). One stroke can never exceed the set strength however often it crosses itself. Emulator-measured: subject target at 40 percent over text behind it (hair under the text 143 to 186 brightness, hair elsewhere and text elsewhere unchanged); text target over the head (214 to 157, text away from head unchanged). NOT checked: export file, bring-back at partial strength, a phone.

- Page-curl delete built 2026-10-15 (Library, phase 4 "quiet motion" item): deleting a creation now rolls the card away like a page lifting off the stack before it is removed, instead of vanishing instantly. `ui/components/PageCurl.kt` (PageCurlBox): records the live card into a GraphicsLayer, snapshots it on delete, and draws the snapshot with `Canvas.drawBitmapMesh` warped onto a vertical cylinder whose radius grows so the roll never self-overlaps; vertex colours shade the curve, a soft gradient shadow sits on the flat page at the fold, and the sheet fades over the last ~28 percent. 460 ms, FastOutSlowIn. CreationsScreen wraps each grid item in PageCurlBox + `Modifier.animateItem()`, so neighbours slide into place after the curl; the item is removed from the list and from disk only in `onCurled` (after the animation). Emulator-checked on the Moto G85 AVD: delete rolls the card to the left edge into a thin strip (the mirrored date on the rolled surface confirms the mesh wrap-around), then the grid reflows; deletions persist (count 7 to 6 to 5 across two deletes); no logcat exceptions. NOT checked: a real phone, vertex-colour shading on GPUs that ignore drawBitmapMesh colours (falls back to an unshaded but still-correct curl), very large thumbnails. This was the last named "still to build" item from the earlier CLAUDE.md list; glitch, frames, frost/reveal brushes, music and story/WhatsApp video share were already built in earlier sessions (that list was stale).
