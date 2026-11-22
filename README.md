# VRoom

**VRoom** turns an Android phone into the tracking and display client for a Windows PC VR system.

## Current implementation

- Android reads the phone rotation vector and sends head orientation over UDP.
- MediaPipe detects up to two hands locally on the phone and sends 21 landmarks per hand to the PC.
- Windows runs MoveNet Lightning locally on the selected PC webcam and detects 17 full-body keypoints.
- Windows has a desktop dashboard for receiving head/hand telemetry and visualizing the PC-webcam skeleton.
- Android sends a throttled JPEG camera preview to Windows over TCP port 9001.
- The Android **VR session** runs the phone IMU, MediaPipe hand tracking, camera uplink, and stereo-display receiver together in one Activity. This prevents the camera/hand tracker from being paused when the display activity opens.
- The SteamVR OpenVR driver registers a virtual HMD, a display-redirect device, and left/right hand endpoints with OpenVR skeletal input components. Hand-pose v2 is relayed to the driver, which maps MediaPipe hand-centred landmarks into the 31-bone OpenVR hierarchy and updates both skeletal motion ranges. For controller-oriented VR titles, a detected thumb-index pinch supplies the analog trigger value/click and a detected fist supplies the squeeze value/click. These are gesture-derived inputs, not physical controller buttons; there is no thumbstick yet. The driver publishes HMD-relative wrist root poses using a monocular palm-size estimate (nominal 85 mm palm width and assumed camera focal scale). This is an approximate bootstrap, not calibrated depth tracking.
- The display-redirect path captures the SteamVR compositor texture, reads it back through D3D11, JPEG-encodes it, and sends it to the Windows host over loopback TCP 9004.
- The Windows host relays those frames over TCP 9002 to Android. The Android VR display crops the side-by-side frame into left/right eye images.

**Validation status:** the end-to-end stereo transport is implemented in code, but still requires testing on the target PC, SteamVR runtime, Android phone, and headset optics. The current GPU readback plus JPEG path is a functional transport baseline, not a low-latency video solution. Do not assume a stable refresh rate or acceptable motion-to-photon latency until measured on hardware.

## What is not implemented yet

- Calibrated room-space hand poses and validated rendered articulated hand meshes. The current tracked hand root is only a monocular palm-width estimate with a nominal 85 mm scale and assumed camera focal length. It is not a measured 6DoF hand pose. OpenVR hand endpoints are controller-class tracked devices; pinch and fist gestures are currently mapped to trigger and squeeze, but there is no thumbstick, menu/A/B input, or validated Roblox interaction profile. Roblox compatibility remains untested.
- Calibrated 3D body/hand tracking. MoveNet output from one PC webcam is 2D image-space keypoints only.
- Reliable 6DoF head position. The phone IMU supplies orientation, not absolute position.
- Working arm-swing or walk-in-place locomotion injected into SteamVR.
- A spatial VR overlay that can interact with the Windows desktop.
- OpenXR support.
- Validated compatibility with individual SteamVR games.

The current system is an early OpenVR integration with an approximate monocular hand-root estimate, not a finished or universally compatible VR headset. Roblox uses OpenXR and expects a compatible controller interaction profile; VRoom's Oculus Touch remapping is a compatibility attempt, not a verified guarantee. Test Roblox's VR mode and SteamVR input bindings on hardware before claiming support.

## Tracking protocol

The Windows host receives UDP telemetry on port 9000 and length-prefixed JPEG streams over TCP:

- `head_pose` v1 — normalized device orientation quaternion, relayed to the SteamVR driver through UDP 9003.
- `hand_pose` v1 — up to two hands, each with 21 image-space landmarks.
- TCP 9001 — JPEG camera frames from the Android phone, each prefixed by a 4-byte big-endian frame length.
- TCP 9002 — side-by-side SteamVR compositor frames relayed from the Windows host to Android.
- TCP 9004 — local loopback stream from the OpenVR driver's compositor-frame capture to the Windows host.
- UDP 9005 — local loopback hand-pose relay from the Windows host to the OpenVR driver. For v2, Windows forwards the complete packet; the driver consumes handedness, confidence, 21 MediaPipe world landmarks, and an estimated HMD-local wrist root per hand. Legacy v1 packets remain accepted by Windows but do not produce a skeletal pose.

The phone clamps normalized hand x/y coordinates to [0, 1]. Landmark z is relative model depth and may be negative. Windows visualizes image-space hand landmarks. MediaPipe world landmarks remain hand-centred. `rootPosition` is a rough HMD-local wrist estimate inferred from the apparent index-to-pinky MCP span, a nominal 85 mm palm breadth, and an assumed focal scale. It is rotated by the HMD pose and submitted as the tracked hand root. Camera intrinsics, actual palm breadth, camera-to-HMD offset/orientation, and hand-depth accuracy still require calibration; expect positional error until those are measured.

## Run

1. Connect the phone and PC to the same trusted Wi-Fi network.
2. Start **VRoom.exe** on Windows and press **Запустить VRoom**.
3. Copy one of the PC's local IPv4 addresses shown in the network card.
4. Enter that address in VRoom on Android and connect.
5. On Android, tap **Запустить VR-сеанс**. This starts head IMU, hand tracking, camera uplink, and the incoming stereo display together.
6. Allow VRoom through Windows Firewall on private networks only.

The Windows host listens on UDP 9000, TCP 9001 (phone camera upload), and TCP 9002 (VR display download). The SteamVR driver separately listens on loopback UDP 9003 for head pose and loopback TCP 9004 for compositor-frame relay. Start VRoom Desktop before connecting the phone. Do not expose these ports to the public internet.

## Roblox / OpenXR setup

VRoom's headset and hand devices are currently exposed through the SteamVR **OpenVR driver**. Roblox uses the OpenXR path, so SteamVR must be selected as the active OpenXR runtime on the PC; enabling Roblox's in-game VR toggle alone does not make an OpenVR-only device visible to Roblox.

1. Install and start SteamVR.
2. In SteamVR, open **Settings → OpenXR** and choose **Set SteamVR as OpenXR Runtime** (the wording can vary by SteamVR version).
3. Restart SteamVR after installing or updating the VRoom driver. Confirm that SteamVR shows the VRoom HMD as the active headset, not only that the VRoom phone app says it is connected.
4. Start Roblox only after SteamVR is running, then enable VR in a supported VR experience.

The VRoom controller profile includes a SteamVR remapping layout intended to map common Oculus Touch bindings. This is only a compatibility mapping attempt: Roblox detection, controller actions, and individual experiences still require testing on the target PC. If Roblox remains flat-screen, check that SteamVR is the active OpenXR runtime and that SteamVR itself reports an active HMD.

## Builds

GitHub Actions publishes:
- `VRoom-Windows-x64` — self-contained Windows desktop application.
- `VRoom-Android-debug` — Android debug APK.
- `VRoom-SteamVR-driver` — SteamVR OpenVR driver package.

Open the repository's Actions tab, select the latest successful **Build VRoom** run, and download the corresponding artifacts.

## Project layout

- `Android/` — Android phone client (IMU, camera hand tracking, network telemetry, VR display).
- `Windows/` — Windows desktop host and tracking dashboard.
- `SteamVRDriver/` — OpenVR HMD, display-redirect, and skeletal-hand driver.
- `docs/ROADMAP.md` — implementation milestones and remaining work.
- `docs/TEST_PLAN.md` — staged hardware validation checklist.

## Windows desktop features

The desktop application has tabs for the phone camera stream, PC webcam body tracking, and VR/motion profile settings. The PC webcam tab runs MoveNet SinglePose Lightning through ONNX Runtime, draws the 17 detected body keypoints over the webcam preview, and reports detection confidence. The model is downloaded on first use to `%LOCALAPPDATA%\\VRoom\\models` and checked against its SHA-256 digest.

Overlay placement, scale, distance, locomotion mode, sensitivity, and smoothing values can be saved to `%LOCALAPPDATA%\\VRoom\\settings.json`. These are configuration profiles only; they do not yet control an in-game overlay or locomotion engine.

## Tracking limitations

Android runs MediaPipe Hand Landmarker on the phone camera and sends 21 landmarks per detected hand over UDP 9000. Full-body pose inference is performed on the Windows PC from its selected webcam using MoveNet Lightning (17 keypoints). The PC webcam skeleton is a 2D image-space visualization, not calibrated 3D room tracking. Phone camera remains available for hand tracking and video preview.
