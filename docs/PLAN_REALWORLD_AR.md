# Plan: Radial Create menu + real-world AR video (Simulon-inspired)

Status: **proposed, not started** (2026-11-01). Agreed direction: plan both first; the video mode
aims at Simulon-style AR compositing. This doc is the plan to approve before any code.

## Honest framing: what we can and can't match
[Simulon](https://apps.apple.com/us/app/simulon/id6670765943) composites 3D CGI into phone video
with camera tracking and **neural, physically-based relighting** (it reconstructs scene lighting and
re-renders virtual objects to match). That relighting is its special sauce and is **research-grade** —
not replicable on-device by a solo Android app.

What Android gives us toward the same *feeling*:
- **ARCore** — 6DoF camera tracking, plane detection, **Depth API** (real objects occlude virtual),
  and **Environmental HDR light estimation** (main light direction + intensity, ambient spherical
  harmonics, and a reflection cubemap). This is the closest on-device substitute for Simulon's
  relighting: good, not movie-grade.
- **Filament / SceneView** — Google's PBR renderer; renders glTF models with image-based lighting
  from ARCore's HDR cubemap → believable lighting, reflections and shadows.

So the realistic target is **"place Hinterlay's 3D text / the depth clock into the real world, lit by
the real scene, with correct occlusion, and record it to MP4."** On-brand (it's the depth clock set
free into 3D space), impressive, and shippable — without promising Simulon's neural renderer.

## Part A — Radial / pie Create menu  (small, do first)
Tapping the center Create button fans options out in an arc instead of going straight to the picker.
- New `ui/components/RadialCreateMenu.kt` — a Compose overlay: items animate out on an arc from the
  center FAB, each a disc with icon + label; tap-outside / re-tap to dismiss. Use `Motion.enter()/exit()`
  and `Fx` haptics (already in the design system). Black/white, soft, no hard shadows.
- Trigger from the raised camera disc in `ui/components/FloatingNavBar.kt` (today it opens the camera).
  Long-press or tap → menu; keep a quick path to the camera.
- Items → existing routes via `MainActivity` deep-link actions: **Text behind photo** (`pick`/`create`),
  **Depth clock** (`clock`), **Camera** (`camera`), **Stickers** (`stickers`), and **Real-world video**
  (new; disabled/"soon" until Part B lands).
- No new dependencies. ~1–2 sessions. This is the piece to build first.

## Part B — Real-world AR video  (large, phased)
### Dependencies to add
- `com.google.ar:core` (ARCore) and **SceneView** (`io.github.sceneview:arsceneview`) which wraps
  ARCore + Filament with a Compose-friendly `ARScene`. Alternative: Filament directly (more control,
  more code). Recommend SceneView to start.
- APK size will grow notably (AR + Filament native libs, roughly +15–30 MB on top of the current ~98 MB).
  Consider an **AAB with ARCore as an on-demand/feature split** later to keep base size down.

### Device gating (required)
- Check `ArCoreApk.checkAvailability()` + "Google Play Services for AR" install. On unsupported devices,
  **hide the AR option** and offer the 2D fallback below. (Moto G85 should support ARCore.)

### Phases
1. **AR foundation** — add SceneView; an `ui/ar/ArVideoScreen.kt` with a live `ARScene`; detect a plane;
   place one test glTF object anchored in the world; enable **Environmental HDR** lighting and **Depth**
   occlusion. Verify tracking + occlusion on the phone.
2. **Hinterlay content in AR** — replace the test object with **3D text** (extruded glyph from the chosen
   font) and/or the **clock** as a 3D model; carry over color and the Phase-2-style treatments that make
   sense in 3D. Place/scale/rotate by touch.
3. **Record to MP4** — capture composited AR frames to video. Reuse `export/VideoEncoder.kt` (it already
   muxes H.264 + AAC from drawn frames); feed it either via Filament readback to a MediaCodec input
   surface (fast path) or `PixelCopy` per frame (simpler, slower). Add a record button + progress
   (`HairlineProgress`), then hand to the existing share (Story / WhatsApp).
4. **Polish** — contact shadow under the object, reflection strength from the HDR cubemap, a clean
   capture UI, performance pass (target 30 fps on mid-range), and graceful fallback messaging.

### 2D fallback (ships value even without AR) 
"Text / clock **behind a moving subject**": run `mlkit.subject.segmentation` per frame on a recorded or
picked video, composite the layer behind the subject mask, export via `VideoEncoder`. No ARCore needed,
works on every device, and is the natural extension of the depth clock to motion. Recommended as the
fallback path for non-AR phones (and a faster first win if Part B's AR proves too heavy).

## Risks / unknowns
- Real-time AR + PBR + recording is GPU-heavy; frame capture is the trickiest bit.
- App size and build complexity rise; glTF/3D-text asset pipeline is new to this project.
- ARCore Environmental HDR ≠ Simulon's neural relighting — set expectations in-app and on the store.
- Timeline: Part A is days; Part B is multi-week. Suggest shipping Part A + the 2D fallback first, then
  the full AR mode.

## Suggested order
1. Part A radial menu. 2. 2D "behind a moving subject" video (fallback, broad device support).
3. Part B AR phases 1→4. Re-evaluate after Phase 1 whether the AR fidelity justifies the size/complexity.
