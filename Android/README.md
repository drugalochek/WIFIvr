# Android client

Planned first milestone:
1. Read orientation from Android's rotation-vector sensor.
2. Pair with the Windows host by local IPv4 address.
3. Send versioned head-pose packets to UDP port 9000.
4. Add camera hand tracking after telemetry is stable.

The Android app will use Kotlin. Camera permission and foreground-service behavior will be added with the camera streaming milestone, not requested prematurely.
