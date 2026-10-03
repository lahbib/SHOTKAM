# ShootCam

Turns an Android phone mounted on a rifle into a shooting camera.
Video runs in a **ring buffer**; every shot saves **N s before + M s after**
(30 s / 20 s by default), with hunting data burned into the image.

## Features

| Area | Details |
|---|---|
| Pre/post-shot recording | Already-encoded H.264 + AAC kept in RAM, cut on keyframes, no re-encoding |
| Shot detection | Recoil (accelerometer), gunshot sound (microphone), either, or both |
| Aim detection | Sudden shouldering rotation (gyroscope) + settle, optionally barrel level |
| Overlay | Date/time, GPS + accuracy + altitude, weather (Open-Meteo), compass heading, elevation, cant, red dot, "TIR #n" marker |
| Video | OpenGL pipeline: always upright, preview shows exactly what is recorded, pinch-to-zoom |
| Clips | Gallery with thumbnails, play, share, delete; GPS location embedded in the MP4 |
| Calibration | Guided wizard measures your rifle's recoil and your shouldering motion |
| Background | Foreground service: keeps running with the screen off, "Save / Disarm" notification |

## Using it
1. Mount the phone on the rifle, lens towards the target.
2. Tap **ARMER**. Status turns blue while the buffer fills, then green (**PRÊT**).
3. Shoot. Status turns red during the post-shot window; the clip lands in **Clips**.
4. Volume key or **Sauver** = manual save.

First time: **Réglages → Calibrer la détection** (1 minute), then **Point** to place
the red dot on your point of impact (tap the image).

## Architecture

```
Camera2 ──► SurfaceTexture ──► OpenGL (rotate + overlay) ──┬─► H.264 encoder ─┐
                                                           └─► preview         ├─► ClipBuffer ─► MP4
AudioRecord ─────────────────────────────────► AAC encoder ───────────────────┘
Accelerometer / gyroscope / rotation vector ─► detectors ─► shot / aim / attitude
LocationManager + Open-Meteo ─► telemetry ─► overlay
```

Samples are stamped with a common clock when they leave their encoder; the muxer
re-aligns audio and video from those stamps, so camera and audio timebases never matter.

## Building
- **GitHub**: push → Actions → `ShootCam-apk` artifact (unit tests run first).
- **GitLab**: `.gitlab-ci.yml` included.
- Local: `./gradlew testReleaseUnitTest assembleRelease` (JDK 17 + Android SDK 35).

Releases are signed with `app/shootcam-release.jks` (sideloading only) so each new build
installs over the previous one. Override the passwords with `SHOOTCAM_STORE_PASSWORD` /
`SHOOTCAM_KEY_PASSWORD` if you replace the key.

Android 10+ (minSdk 29). Permissions: camera, microphone, location, notifications.

## Known limitations
- Cuts are keyframe-aligned (1 s): the pre-shot window may be up to 1 s longer.
- Compass heading is disturbed by the steel of the rifle; treat it as indicative.
- Weather needs network access; without it the weather line is simply omitted.
- Some vendors (Xiaomi, Huawei, Samsung) kill background services:
  disable battery optimisation for ShootCam.
- A single clip is capped at 3 minutes; continuous shooting starts a new clip.
