# VRoom development roadmap

## Milestone 0 — identity and telemetry
- [x] Rename Android product identity to VRoom
- [x] Define head-pose packet v1
- [x] Define hand-pose packet v1
- [x] Android head orientation sender
- [x] Android local MediaPipe hand tracking and UDP sender
- [x] Windows telemetry receiver and desktop dashboard
- [x] Android-to-Windows camera preview stream over TCP
- [x] Windows camera preview panel
- [x] Clamp normalized image-space hand coordinates at the Android sender
- [x] Add Windows PC webcam preview mode
- [x] Add persisted desktop settings profile for overlay and locomotion
- [x] Add automatic reconnect for the Android camera uplink
- [x] Add MediaPipe full-body pose estimation on Android camera and publish 33 body landmarks
- [x] Render hand and body skeletons over the phone-camera preview in Windows Desktop
- [ ] Add full-body pose estimation from the separate PC webcam and publish joint telemetry
- [ ] Measure packet rate, loss, and end-to-end latency on real hardware

## Milestone 1 — VR runtime
- [ ] Build a SteamVR/OpenVR driver registering the VRoom HMD and two hand controllers
- [ ] Connect driver poses and hand input to validated tracking state
- [ ] Calibrate head neutral direction and map coordinate axes
- [ ] Convert camera landmarks into calibrated controller pose and gesture inputs
- [ ] Validate compatibility with selected PC VR titles

## Milestone 2 — PC-to-phone video
- [ ] Add Android TCP receiver and VR display surface for incoming PC frames
- [ ] Capture desktop/game frames on Windows
- [ ] Encode and stream video to Android over a low-latency transport
- [ ] Render separate left/right eye views on Android
- [x] Add adjustable Android headset-preview FOV control (60°–110°, visual crop/scale only)
- [ ] Add lens distortion and headset geometry calibration
- [ ] Render and interact with a spatial overlay in the Android VR view

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
