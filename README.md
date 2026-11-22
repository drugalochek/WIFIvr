# VRoom

**VRoom** turns an Android phone into the tracking and display client for a Windows PC VR system.

## Current status

- Android app identity and project display name: VRoom.
- Android reads the phone rotation vector and sends head orientation over UDP.
- MediaPipe detects up to two hands locally on the phone and sends 21 landmarks per hand to the PC.
- Windows runs MoveNet Lightning locally on the selected PC webcam and detects 17 full-body keypoints; the phone camera is no longer used for body-pose inference.
- Windows has a desktop dashboard for receiving head/hand telemetry.
- Android sends a throttled JPEG camera preview to Windows over TCP port 9001.
- Windows displays the incoming phone camera stream alongside tracking telemetry.

**Important:** the current OpenVR driver is an initial virtual-HMD integration, not a finished headset. It registers an HMD and forwards phone IMU orientation through the Windows host. Its virtual-display callback does not yet forward compositor frames to the phone. The TCP 9002 path is currently a 15 FPS primary-desktop JPEG test; Android duplicates the same monoscopic frame into both eye panels. This is not true stereo game streaming. Controller registration, calibrated hand poses, 6DoF position, and OpenXR support remain future work.

## Tracking protocol

The Windows app receives UDP telemetry on port 9000 and a length-prefixed JPEG camera stream over TCP port 9001:
- `head_pose` v1 — normalized device orientation quaternion.
- `hand_pose` v1 — up to two hands, each with 21 image-space landmarks.
- TCP 9001 — JPEG camera frames, each prefixed by a 4-byte big-endian frame length. The current Android preview sender targets up to 10 FPS.
- TCP 9002 — primary Windows desktop JPEG preview at approximately 15 FPS, 1280×720. Android shows the same image in both eye panels for transport testing only.

The phone clamps x/y image coordinates to [0, 1]. Landmark z is relative model depth and may be negative. The receiver stores/display the latest telemetry; it does not infer room-space controller poses from 2D camera landmarks.

## Run

1. Connect the phone and PC to the same trusted Wi-Fi network.
2. Start **VRoom.exe** on Windows and press **Запустить приёмник**.
3. Copy one of the PC's local IPv4 addresses shown in the network card.
4. Enter that address in VRoom on Android and connect.
5. Allow VRoom through Windows Firewall on private networks only.

The Windows host listens on UDP 9000, TCP 9001 (phone camera upload), and TCP 9002 (monoscopic desktop preview download). Start VRoom Desktop before connecting the phone. Allow these ports through Windows Firewall on private networks only. Do not expose them to the public internet.

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

The Windows desktop application has tabs for the phone camera stream, PC webcam body tracking, and VR/motion profile settings. The PC webcam tab runs MoveNet SinglePose Lightning through ONNX Runtime, draws the 17 detected body keypoints over the webcam preview, and reports detection confidence. The model is downloaded on first use to `%LOCALAPPDATA%\\VRoom\\models` and checked against its SHA-256 digest. Full-body pose is currently visualized in the Windows app only; calibrated room-space skeleton tracking and locomotion injection into SteamVR are not implemented yet.

Overlay placement, scale, distance, locomotion mode, sensitivity, and smoothing values can be saved to `%LOCALAPPDATA%\\VRoom\\settings.json`. These are configuration profiles for the upcoming spatial overlay and motion engine, not active in-game controls until the VR runtime is connected.

The Android camera uplink reconnects to TCP port 9001 when VRoom Desktop becomes available again.


## Skeleton tracking

Android runs MediaPipe Hand Landmarker on the phone camera and sends 21 landmarks per detected hand over UDP 9000. Full-body pose inference is performed on the Windows PC from its selected webcam using MoveNet Lightning (17 keypoints). The PC webcam skeleton is a 2D image-space visualization, not calibrated 3D room tracking. Phone camera remains available for hand tracking and video preview.
