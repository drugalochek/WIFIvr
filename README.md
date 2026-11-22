# WiFi VR

Android phone as a wireless VR headset, Windows PC as the host.

## Project status
Initial repository scaffold. First milestone: establish low-latency phone-to-PC head-pose streaming over the local Wi-Fi network.

## Planned components
- `Android/` — Android client (camera, IMU, stereo display, network transport)
- `Windows/` — Windows host (capture, streaming, OpenXR integration)
- `Protocol/` — versioned network message definitions

## Development principles
- Keep head-pose and hand-tracking telemetry separate from video transport.
- Use a local-network pairing flow; do not expose the receiver to the public internet.
- Treat true SteamVR/OpenXR support as a dedicated integration milestone, not as simple screen mirroring.
