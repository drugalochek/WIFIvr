# WiFi VR

Android phone as a wireless VR headset, Windows PC as the host.

## Project status
Initial repository scaffold. First milestone: establish low-latency phone-to-PC head-pose streaming over the local Wi-Fi network.

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
