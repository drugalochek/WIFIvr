# VRoom

**VRoom** turns an Android phone into the tracking and display client for a Windows PC VR system.

## Current status

- Android app identity and project display name: VRoom.
- Android reads the phone rotation vector and sends head orientation over UDP.
- MediaPipe detects up to two hands locally on the phone and sends 21 landmarks per hand to the PC.
- Windows now has a desktop dashboard for receiving and inspecting head/hand telemetry.
- Windows validates incoming packet structure and displays the latest tracking state.

**Important:** this is not yet a SteamVR/OpenXR headset driver. PC game capture, low-latency stereo video streaming, calibrated per-eye rendering, 6DoF positional tracking, and virtual controller registration remain future integration milestones.

## Tracking protocol

The Windows app receives UDP telemetry on port 9000:
- `head_pose` v1 — normalized device orientation quaternion.
- `hand_pose` v1 — up to two hands, each with 21 image-space landmarks.

The phone clamps x/y image coordinates to [0, 1]. Landmark z is relative model depth and may be negative. The receiver stores/display the latest telemetry; it does not infer room-space controller poses from 2D camera landmarks.

## Run

1. Connect the phone and PC to the same trusted Wi-Fi network.
2. Start **VRoom.exe** on Windows and press **Запустить приёмник**.
3. Copy one of the PC's local IPv4 addresses shown in the network card.
4. Enter that address in VRoom on Android and connect.
5. Allow VRoom through Windows Firewall on private networks only.

The receiver listens on UDP port 9000. Do not expose it to the public internet.

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
