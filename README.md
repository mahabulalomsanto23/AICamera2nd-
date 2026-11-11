# AI Camera Pro — Android Studio source project

Kotlin / CameraX Android app targeting Android 8.0+ (API 26), with OnePlus 8 / Snapdragon 865 / Android 13 as the primary device target.

## Implemented

- CameraX live preview and full-quality still capture.
- Four modes: Normal, Vivid, AI, Portrait; AI is selected by default.
- Colorful dark premium-style camera UI with gradient panels and distinct mode colors.
- Front/rear camera switching, rear flash control, tap-to-focus, exposure compensation and zoom.
- DataStore preferences for saving mode, startup mode, portrait blur strength and flash-on-launch.
- Gallery import, draggable before/after wipe comparison, original/enhanced toggle, and JPEG save to `Pictures/AI Camera Pro`.
- Tap-to-focus metering, exposure compensation and digital zoom controls.
- Basic EXIF capture metadata preservation (date/time, camera make/model and selected exposure tags; GPS is intentionally not copied).
- DataStore-backed saving preference: Original + Enhanced (default) or Enhanced Only.
- Vivid and AI image-wide computational color enhancement.
- **AI mode:** bundled on-device ML Kit Image Labeling model classifies broad image content (people, outdoor/nature, food, general) and selects conservative scene-tuning parameters.
- **Portrait mode:** bundled on-device ML Kit Selfie Segmentation model creates a foreground confidence mask; background blur is composited behind the detected person. If inference fails, the app falls back to safe global enhancement.
- No network permission is declared. Model inference and photo processing run locally; no cloud, account, or photo upload is used.

## Offline models

The app bundles:
- `com.google.mlkit:image-labeling:17.0.9` for on-device image labels.
- `com.google.mlkit:segmentation-selfie:16.0.0-beta6` for human foreground segmentation.

These bundled artifacts include their model assets in the app at build time. Image labeling is a broad 400+ label classifier, not a bespoke photographic scene model. Selfie segmentation detects people; it is not a general-purpose object/depth segmentation model. The scene-specific tuning is deliberately conservative and is not generative image restoration.

## Build & install (OnePlus 8 / Android 13)

1. Push to `main` (or run **Actions → Build AI Camera Pro APK → Run workflow**).
2. When it finishes, download the APK from either place:
   - **Releases → "AI Camera Pro - latest build" → `AI-Camera-Pro-OnePlus8.apk`** (direct file, easiest on phone), or
   - **Actions → the run → Artifacts → `AI-Camera-Pro-OnePlus8-APK`** (a zip; extract it first).
3. Install on the phone. If Android asks, allow *Install unknown apps* for your browser/file manager. If Google Play Protect warns, choose *Install anyway* (personal test key, not a Play Store build).
4. **If you installed an older build before, uninstall it first once.** Older builds were signed with a different random key each time, so Android refuses to install over them.

Every build is now signed with the fixed key in `keystore/`, so future builds install as normal updates (no uninstall needed). This is a public personal test key; make a private keystore before any Play Store release.

Build details: one universal-for-arm64 APK (`arm64-v8a`), JDK 17, Gradle 8.7, AGP 8.6.1, Kotlin 1.9.24. Lint warnings do not block the build. CI compilation is separate from physical-device testing.

## Remaining work versus the full product specification

This is an actively developed source project, not yet the complete professional camera system in the supplied specification. Remaining items include:

- More detailed scene and multi-subject understanding; semantic sky, vegetation, skin, hair and object segmentation.
- Advanced exposure/white-balance estimation, selective LAB/HSL color, multi-region local tone mapping and semantic region-aware enhancement. A lightweight local-detail contrast pass is implemented.
- Dedicated denoising, edge-aware sharpening, restoration and upscaling models.
- Portrait hair-edge refinement and more advanced edge-aware mask refinement. Four selectable blur-strength presets are implemented.
- Manual ISO/shutter/focus/white-balance controls, resolution and aspect-ratio selection. Tap-to-focus, exposure compensation and zoom are implemented; manual sensor controls depend on device Camera2 support.

- Full EXIF/XMP/GPS preservation and configurable metadata privacy controls.
- MVVM/StateFlow architecture, bounded processing queue, thermal awareness, automated quality tests.
- Physical OnePlus 8 testing and measured image-quality/performance results.

No capability is represented as complete unless it is actually implemented. Physical-device testing must be recorded separately from CI compilation.

## Development environment

- Android Gradle Plugin 8.6.1 / Kotlin 1.9.24
- Gradle 8.7 / JDK 17
- compileSdk 35 / minSdk 26 / targetSdk 35
