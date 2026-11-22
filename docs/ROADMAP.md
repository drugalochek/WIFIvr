# VRoom development roadmap

The checkboxes below distinguish implemented code from hardware validation. A feature is not considered finished merely because it compiles.

## Milestone 0 — identity, telemetry and diagnostics
- [x] Rename Android product identity to VRoom
- [x] Define head-pose packet v1
- [x] Define hand-pose packet v1
- [x] Android head-orientation sender
- [x] Android local MediaPipe hand tracking and UDP sender
- [x] Windows telemetry receiver and desktop dashboard
- [x] Android-to-Windows camera preview stream over TCP 9001
- [x] Windows camera preview panel
- [x] Add Windows PC webcam preview mode
- [x] Add persisted desktop settings profile for overlay and locomotion
- [x] Add automatic reconnect for the Android camera uplink
- [x] Keep MediaPipe hand tracking on the phone camera and publish 21 landmarks per hand
- [x] Move full-body pose inference off the phone camera
- [x] Add MoveNet Lightning (ONNX Runtime) full-body pose estimation from the separate PC webcam
- [x] Render the 17 detected PC-webcam body keypoints over the PC webcam preview
- [ ] Add live packet-rate, packet-loss, and end-to-end latency telemetry
- [ ] Validate camera selection, skeleton stability, and model performance on real hardware

## Milestone 1 — SteamVR runtime and controllers
- [x] Compile an initial SteamVR/OpenVR server driver registering a virtual VRoom HMD
- [x] Forward phone IMU orientation through the Windows host to the OpenVR driver
- [x] Register a separate OpenVR display-redirect device
- [x] Register left/right OpenVR hand endpoints and create `/input/skeleton/left` and `/input/skeleton/right` components
- [x] Relay complete hand-pose v2 packets through Windows to the OpenVR driver
- [x] Map MediaPipe's 21 hand-centred world landmarks into the 31-bone OpenVR parent-space hierarchy
- [x] Estimate an initial HMD-local wrist root from apparent palm width and publish a tracked hand pose relative to the HMD
- [x] Update both `WithController` and `WithoutController` skeletal motion ranges; do not synthesize trigger/grip buttons
- [ ] Compile and validate the skeletal driver against the target SteamVR runtime
- [ ] Verify bone orientation, handedness, and articulated hand rendering in a SteamVR skeletal-input test application
- [ ] Replace the nominal palm-width/focal-scale root estimate with measured camera intrinsics and per-user palm calibration
- [ ] Calibrate camera-to-HMD position/orientation and verify left/right coordinate axes in the headset
- [ ] Add neutral-pose calibration and coordinate-axis mapping
- [ ] Validate controller poses and inputs in SteamVR's device view and test applications
- [ ] Validate compatibility with selected PC VR titles

## Milestone 2 — PC-to-phone stereo display
- [x] Add OpenVR display-redirect callback for compositor frames
- [x] Capture compositor texture on Windows through D3D11
- [x] Encode captured frames as JPEG and relay them over loopback TCP 9004
- [x] Relay compositor frames from Windows to Android over TCP 9002
- [x] Add Android TCP receiver and VR display surface
- [x] Run head IMU, MediaPipe hand tracking, camera uplink, and stereo display receiver in one Android VR session
- [x] Split side-by-side frames into left/right eye regions on Android
- [ ] Validate actual SteamVR compositor frames arrive continuously on the phone
- [ ] Measure frame rate, frame age, dropped frames, and motion-to-photon latency
- [ ] Replace or optimize GPU readback/JPEG transport for low-latency streaming
- [ ] Add headset lens distortion and geometry calibration
- [ ] Validate eye alignment, scaling, focus, and comfort in the physical headset

## Milestone 3 — calibrated tracking and locomotion
- [ ] Add camera calibration for the PC webcam and define a reproducible tracking volume
- [ ] Estimate usable 3D body/hand positions using a validated depth source or multi-view tracking
- [ ] Publish timestamped, calibrated body/hand tracking data to the VR runtime
- [ ] Add confidence gating, smoothing, and loss-of-tracking recovery
- [ ] Implement arm-swing locomotion from validated tracked motion
- [ ] Implement walk-in-place only after reliable leg tracking is available
- [ ] Add safe enable/disable controls and movement sensitivity limits
- [ ] Validate locomotion in a controlled test scene before game integration

## Milestone 4 — spatial interface and positional tracking
- [ ] Render a spatial overlay in the Android VR view
- [ ] Add head-locked and world-anchored overlay modes
- [ ] Add wrist-anchored overlay only after controller tracking is calibrated
- [ ] Implement desktop pointer, click, and keyboard interaction
- [ ] Add reliable 6DoF head position tracking or supported external tracking
- [ ] Add reconnect handling, telemetry health, and diagnostics
- [ ] Test network loss, device rotation, thermal performance, and recovery
- [ ] Evaluate OpenXR support after the OpenVR path is stable

## Engineering constraints
- Keep control telemetry separate from video transport.
- Camera landmarks are image-space points. MediaPipe world landmarks are hand-centred estimates, not calibrated camera-space or room-space positions. The skeletal driver can animate a hand-local skeleton but cannot locate its wrist in the VR room until camera-to-HMD calibration or another validated depth/position source is available.
- A phone IMU provides orientation, not reliable 6DoF position by itself.
- A single RGB webcam's 2D pose output must not be presented as measured 3D tracking.
- Do not inject locomotion from noisy or uncalibrated body estimates.
- Never expose the UDP receiver or driver relay directly to the public internet.
- Do not describe the system as fully SteamVR/OpenXR-compatible until runtime integration is implemented and tested on hardware.
