# Veyra

**Veyra** — короткое имя проекта: ассоциация с виртуальным пространством и движением. Репозиторий пока сохраняет техническое имя `WIFIvr`.

Android phone as a wireless VR headset, Windows PC as the host.

## Project status
The current prototype streams head orientation and checks hand landmarks. Room-scale positional tracking and boundary setup are planned as the next major milestone.

## Tracking protocol

The Windows receiver accepts separate UDP telemetry packets on port 9000:
- `head_pose` v1 — normalized device orientation quaternion.
- `hand_pose` v1 — up to two hands, each with 21 normalized image-space landmarks.

The receiver validates packet structure and keeps the latest head and hand snapshots in `TrackingState` for the next runtime integration layer. This is tracking transport only: it does not yet register an OpenXR headset or controllers.

See `Protocol/head-pose.v1.schema.json` and `Protocol/hand-pose.v1.schema.json` for the packet contracts.

## Planned components
- `Android/` — Android client (camera, IMU, stereo display, network transport)
- `Windows/` — Windows host (capture, streaming, OpenXR integration)
- `Protocol/` — versioned network message definitions

## Automatic builds

GitHub Actions builds the Windows receiver on every push to `main`, on pull requests targeting `main`, and on manual dispatch.

To download a build:
1. Open the repository's **Actions** tab.
2. Select the latest **Build WiFiVR** run.
3. In **Artifacts**, download `WiFiVR-Receiver-win-x64`.

The artifact contains a self-contained Windows x64 build, so a separate .NET runtime installation is not required. Android builds will be added after the Android Gradle project is committed.

## Development principles
- Keep head-pose and hand-tracking telemetry separate from video transport.
- Use a local-network pairing flow; do not expose the receiver to the public internet.
- Treat true SteamVR/OpenXR support as a dedicated integration milestone, not as simple screen mirroring.
