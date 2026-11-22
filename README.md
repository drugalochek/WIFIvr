# VRoom

**VRoom** turns an Android phone into the tracking and display client for a Windows PC VR system.

## Current status

- Android app identity and project display name: VRoom.
- Android reads the phone rotation vector and sends head orientation over UDP.
- MediaPipe detects up to two hands locally on the phone and sends 21 landmarks per hand to the PC.
- Windows has a desktop dashboard for receiving head/hand telemetry.
- Android sends a throttled JPEG camera preview to Windows over TCP port 9001.
- Windows displays the incoming phone camera stream alongside tracking telemetry.

**Important:** this is not yet a SteamVR/OpenXR headset driver. PC game capture, low-latency stereo video streaming, calibrated per-eye rendering, 6DoF positional tracking, and virtual controller registration remain future integration milestones.

## Tracking protocol

The Windows app receives UDP telemetry on port 9000 and a length-prefixed JPEG camera stream over TCP port 9001:
- `head_pose` v1 — normalized device orientation quaternion.
- `hand_pose` v1 — up to two hands, each with 21 image-space landmarks.
- TCP 9001 — JPEG camera frames, each prefixed by a 4-byte big-endian frame length. The current Android preview sender targets up to 10 FPS.

The phone clamps x/y image coordinates to [0, 1]. Landmark z is relative model depth and may be negative. The receiver stores/display the latest telemetry; it does not infer room-space controller poses from 2D camera landmarks.

## Run

1. Connect the phone and PC to the same trusted Wi-Fi network.
2. Start **VRoom.exe** on Windows and press **Запустить приёмник**.
3. Copy one of the PC's local IPv4 addresses shown in the network card.
4. Enter that address in VRoom on Android and connect.
5. Allow VRoom through Windows Firewall on private networks only.

The Windows host listens on UDP 9000 and TCP 9001. Start VRoom Desktop before opening the phone hand-tracking screen so the camera stream can connect. Allow both ports through Windows Firewall on private networks only. Do not expose them to the public internet.

## Builds

GitHub Actions publishes:
- `VRoom-Windows-x64` — self-contained Windows desktop application.
- `VRoom-Android-debug` — Android debug APK.

Open the repository's Actions tab, select the latest successful **Build VRoom** run, and download the corresponding artifact.

## Project layout

- `Android/` — Android phone client (IMU, camera hand tracking, network telemetry).
- `Windows/` — Windows desktop host and tracking dashboard.
- `Protocol/` — versioned network message schemas.
- `docs/ROADMAP.md` — implementation milestones.

## Next engineering milestones

1. Build and validate a SteamVR/OpenVR driver registering one HMD and two controllers.
2. Map head orientation and calibrated hand tracking into runtime device poses and inputs.
3. Capture PC game frames and stream a low-latency stereo video feed to Android.
4. Add lens calibration, stereo geometry, timing and latency measurements.


## VRoom Desktop features

The Windows desktop application has tabs for the phone camera stream, an optional PC webcam preview, and VR/motion profile settings. The PC webcam tab currently captures and displays a selected local camera; full-body pose estimation and joint streaming are not implemented yet.

Overlay placement, scale, distance, locomotion mode, sensitivity, and smoothing values can be saved to `%LOCALAPPDATA%\\VRoom\\settings.json`. These are configuration profiles for the upcoming spatial overlay and motion engine, not active in-game controls until the VR runtime is connected.

The Android camera uplink reconnects to TCP port 9001 when VRoom Desktop becomes available again.


## Skeleton tracking and FOV

Android now runs MediaPipe Hand Landmarker and Pose Landmarker on the phone camera. It sends 21 landmarks per detected hand and up to 33 body landmarks over UDP 9000. VRoom Desktop draws the hand and body skeletons over the received phone-camera preview. This is 2D image-space visualization, not calibrated 3D room tracking. The optional PC webcam tab remains a preview-only capture path; PC-webcam body inference is not implemented yet.

The Android headset preview includes a persistent FOV control from 60° to 110°. It changes the preview crop/scale for visual adjustment; it is not a replacement for physical lens calibration or the eventual PC-to-phone stereo display path.
