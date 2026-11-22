# VRoom development roadmap

## Milestone 0 — identity and telemetry
- [x] Rename Android product identity to VRoom
- [x] Define head-pose packet v1
- [x] Define hand-pose packet v1
- [x] Android head orientation sender
- [x] Android local MediaPipe hand tracking and UDP sender
- [x] Windows telemetry receiver and desktop dashboard
- [x] Clamp normalized image-space hand coordinates at the Android sender
- [ ] Measure packet rate, loss, and end-to-end latency on real hardware

## Milestone 1 — VR runtime
- [ ] Build a SteamVR/OpenVR driver registering the VRoom HMD and two hand controllers
- [ ] Connect driver poses and hand input to validated tracking state
- [ ] Calibrate head neutral direction and map coordinate axes
- [ ] Convert camera landmarks into calibrated controller pose and gesture inputs
- [ ] Validate compatibility with selected PC VR titles

## Milestone 2 — PC-to-phone video
- [ ] Capture desktop/game frames on Windows
- [ ] Encode and stream video to Android over a low-latency transport
- [ ] Render separate left/right eye views on Android
- [ ] Add lens distortion and headset geometry calibration

## Milestone 3 — positional tracking and quality
- [ ] Add reliable 6DoF position tracking or supported external tracking
- [ ] Measure motion-to-photon latency and frame pacing
- [ ] Add reconnect handling, telemetry health and diagnostics
- [ ] Test network loss, device rotation and thermal performance

## Engineering constraints
- Keep control telemetry separate from video transport.
- Camera landmarks are image-space points, not calibrated room-space controller poses.
- A phone IMU provides orientation, not reliable 6DoF position by itself.
- Never expose the UDP receiver directly to the public internet.
- Do not describe the system as SteamVR/OpenXR-compatible until the driver and runtime integration are implemented and tested.
