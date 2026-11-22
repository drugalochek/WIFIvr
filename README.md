# VRoom

**VRoom** turns an Android phone into the tracking and display client for a Windows PC VR system.

## Current implementation

- Android reads the phone rotation vector and sends head orientation over UDP.
- MediaPipe detects up to two hands locally on the phone and sends 21 landmarks per hand to the PC.
- Windows runs MoveNet Lightning locally on the selected PC webcam and detects 17 full-body keypoints.
- Windows has a desktop dashboard for receiving head/hand telemetry and visualizing the PC-webcam skeleton.
- Android sends a throttled JPEG camera preview to Windows over TCP port 9001.
- The SteamVR OpenVR driver registers a virtual HMD and a separate display-redirect device.
- The display-redirect path captures the SteamVR compositor texture, reads it back through D3D11, JPEG-encodes it, and sends it to the Windows host over loopback TCP 9004.
- The Windows host relays those frames over TCP 9002 to Android. The Android VR display crops the side-by-side frame into left/right eye images.

**Validation status:** the end-to-end stereo transport is implemented in code, but still requires testing on the target PC, SteamVR runtime, Android phone, and headset optics. The current GPU readback plus JPEG path is a functional transport baseline, not a low-latency video solution. Do not assume a stable refresh rate or acceptable motion-to-photon latency until measured on hardware.

## What is not implemented yet

- Two tracked OpenVR hand-controller devices with calibrated poses and working input components.
- Calibrated 3D body/hand tracking. MoveNet output from one PC webcam is 2D image-space keypoints only.
- Reliable 6DoF head position. The phone IMU supplies orientation, not absolute position.
- Working arm-swing or walk-in-place locomotion injected into SteamVR.
- A spatial VR overlay that can interact with the Windows desktop.
- OpenXR support.
- Validated compatibility with individual SteamVR games.

The current system is an early OpenVR integration, not a finished or universally compatible VR headset. Roblox and other games only work when their own VR support permits it.

## Tracking protocol

The Windows host receives UDP telemetry on port 9000 and length-prefixed JPEG streams over TCP:

- `head_pose` v1 — normalized device orientation quaternion, relayed to the SteamVR driver through UDP 9003.
- `hand_pose` v1 — up to two hands, each with 21 image-space landmarks.
- TCP 9001 — JPEG camera frames from the Android phone, each prefixed by a 4-byte big-endian frame length.
- TCP 9002 — side-by-side SteamVR compositor frames relayed from the Windows host to Android.
- TCP 9004 — local loopback stream from the OpenVR driver's compositor-frame capture to the Windows host.

The phone clamps normalized hand x/y coordinates to [0, 1]. Landmark z is relative model depth and may be negative. The receiver currently visualizes hand landmarks; it does not infer room-space controller poses from them.

## Run

1. Connect the phone and PC to the same trusted Wi-Fi network.
2. Start **VRoom.exe** on Windows and press **Запустить VRoom**.
3. Copy one of the PC's local IPv4 addresses shown in the network card.
4. Enter that address in VRoom on Android and connect.
5. Start the Android VR display activity when testing the PC-to-phone display path.
6. Allow VRoom through Windows Firewall on private networks only.

The Windows host listens on UDP 9000, TCP 9001 (phone camera upload), and TCP 9002 (VR display download). The SteamVR driver separately listens on loopback UDP 9003 for head pose and loopback TCP 9004 for compositor-frame relay. Start VRoom Desktop before connecting the phone. Do not expose these ports to the public internet.

## Builds

GitHub Actions publishes:
- `VRoom-Windows-x64` — self-contained Windows desktop application.
- `VRoom-Android-debug` — Android debug APK.
- `VRoom-SteamVR-driver` — SteamVR OpenVR driver package.

Open the repository's Actions tab, select the latest successful **Build VRoom** run, and download the corresponding artifacts.

## Project layout

- `Android/` — Android phone client (IMU, camera hand tracking, network telemetry, VR display).
- `Windows/` — Windows desktop host and tracking dashboard.
- `SteamVRDriver/` — OpenVR HMD and display-redirect driver.
- `docs/ROADMAP.md` — implementation milestones and remaining work.
- `docs/TEST_PLAN.md` — staged hardware validation checklist.

## Windows desktop features

The desktop application has tabs for the phone camera stream, PC webcam body tracking, and VR/motion profile settings. The PC webcam tab runs MoveNet SinglePose Lightning through ONNX Runtime, draws the 17 detected body keypoints over the webcam preview, and reports detection confidence. The model is downloaded on first use to `%LOCALAPPDATA%\\VRoom\\models` and checked against its SHA-256 digest.

Overlay placement, scale, distance, locomotion mode, sensitivity, and smoothing values can be saved to `%LOCALAPPDATA%\\VRoom\\settings.json`. These are configuration profiles only; they do not yet control an in-game overlay or locomotion engine.

## Tracking limitations

Android runs MediaPipe Hand Landmarker on the phone camera and sends 21 landmarks per detected hand over UDP 9000. Full-body pose inference is performed on the Windows PC from its selected webcam using MoveNet Lightning (17 keypoints). The PC webcam skeleton is a 2D image-space visualization, not calibrated 3D room tracking. Phone camera remains available for hand tracking and video preview.
