# ShootCam

Turns an Android phone mounted on a rifle into a shooting camera:
video runs in a **ring buffer**, and every shot saves
**N s before + M s after** (30 s / 20 s by default, configurable).

## How it works

| Component | Details |
|---|---|
| Buffer | Already-encoded H.264 video + AAC audio kept in RAM (~40 MB for 30 s at 1080p) |
| Shot detection | Acceleration peak (recoil) and/or sound peak (gunshot) — 4 modes |
| Aiming | Sudden rotation (gyroscope) followed by ≥ 250 ms of stability → arms the camera |
| Rapid shots | A shot during the post-shot window extends the same clip (file name: `_3tirs`) |
| Output | MP4 in **Movies/ShootCam** (gallery), no re-encoding |
| Background | Foreground service: keeps running with the screen off, "Save / Stop" notification |

### Arming modes
- **On aim** (default): sensors only → camera starts when the rifle is shouldered.
  The pre-shot window therefore starts at the aiming motion. Disarms after inactivity (120 s).
- **Always on**: camera always running, full pre-shot window guaranteed, uses more battery.

## Calibration (once, at the range)
1. Start the app, mount the phone, shoot.
2. Read the `Accél`, `Gyro` and `Son` peaks on screen.
3. Settings → recoil threshold ≈ 70% of the observed peak; aim threshold slightly below the gyro peak of a shouldering motion.
4. Driven hunts (nearby shooters): use **Recoil AND gunshot** mode.

## Building the APK
- **Android Studio**: open the folder → Run.
- **GitHub**: push → Actions → `ShootCam-apk` artifact.
- **GitLab**: `.gitlab-ci.yml` included → `build-apk` job artifact.
- Local: `./gradlew assembleRelease` (JDK 17 + Android SDK 35). APK signed with the debug key, installable directly.

Android 10+ (minSdk 29). Permissions: camera, microphone, notifications.

## Known limitations
- Cuts are keyframe-aligned (1 s): the pre-shot window may be up to 1 s longer.
- Some vendors (Xiaomi, Huawei, Samsung) kill background services:
  disable battery optimisation for ShootCam.
- 4K / 60 fps is not supported by every sensor (falls back to the best available size).
