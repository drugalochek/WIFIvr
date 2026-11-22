# WiFiVR Android client

The first Android client milestone is implemented in Kotlin.

## Current features
- Reads device orientation from Android `TYPE_ROTATION_VECTOR`.
- Sends the latest quaternion to the Windows host over UDP at approximately 60 Hz.
- Uses protocol `head_pose` version 1 on UDP port `9000`.
- Keeps network sending on a single scheduled worker; sensor callbacks only replace the latest pose, so a slow Wi-Fi link cannot create an unbounded queue.

## Build locally
Open the `Android/` directory in Android Studio, or run:

```bash
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Run
1. Connect the phone and Windows PC to the same trusted local Wi-Fi network.
2. Start the Windows receiver on the PC.
3. Find the PC's local IPv4 address.
4. Open WiFiVR on the phone, enter the PC address, and tap **Connect**.
5. Allow the Windows firewall prompt for the receiver on private networks.

This milestone sends orientation only. It does not yet stream PC video, render a stereo headset view, track hands, or provide an OpenXR runtime.
