# VRoom Android client

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
4. Open VRoom on the phone, enter the PC address, and tap **Connect**.
5. Allow the Windows firewall prompt for the receiver on private networks.

This milestone sends head orientation, includes a camera-based hand landmark preview, and adds a per-user room-area setup screen with ARCore availability detection. Room dimensions are currently a saved rectangular draft only: live ARCore pose tracking, automatic wall scanning, boundary alerts, PC position packets, PC video streaming, stereo headset rendering, and an OpenXR runtime are not implemented yet.

## Room profiles (early setup)

Open **Room and play area** from the home screen. Enter a profile name and the approximate width and length of the safe area; values are stored locally per profile on this phone. The screen checks whether ARCore is supported by the device. ARCore is an optional dependency so phones without it can still use orientation streaming.

The saved rectangle is configuration data, not a detected room mesh or a safety system. Do not use it as a collision warning until live positional tracking and boundary checks are implemented and tested.

## Hand camera check

From the home screen tap **Проверка рук и камеры** and grant camera permission. Hold one or both hands in view; the overlay draws the 21 landmarks and finger connections. The CI workflow downloads the official MediaPipe hand model into the build assets before packaging the APK.

The displayed x/y values are normalized image coordinates and z is relative depth from the hand landmark model. A single RGB camera does **not** provide reliable absolute room-space position or full 3D hand tracking; this screen is a visual detection/alignment check, not a calibrated VR controller yet.
