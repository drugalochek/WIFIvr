# WiFi VR roadmap

## Milestone 0 — repository and telemetry
- [x] Initialize repository
- [x] Define head-pose packet v1
- [x] Add Windows UDP receiver skeleton
- [ ] Add Android sensor sender
- [ ] Test packet rate, loss, and end-to-end latency on a real device

## Milestone 1 — tracking
- [ ] Calibrate neutral head direction
- [ ] Validate coordinate handedness and axis mapping
- [ ] Add camera capture and hand landmarks
- [x] Define hand-pose packet schema
- [x] Extend Windows receiver to validate and retain head/hand tracking snapshots
- [ ] Send MediaPipe hand landmarks from Android to Windows

## Milestone 2 — display transport
- [ ] Capture desktop/game frames
- [ ] Encode and stream video to Android
- [ ] Add side-by-side stereo presentation and lens calibration

## Milestone 3 — VR runtime
- [ ] Integrate an OpenXR runtime/driver path (native runtime/driver work remains separate from the UDP receiver)
- [ ] Map head pose and hand poses to the runtime
- [ ] Validate compatibility with selected PC VR titles

## Engineering constraints
- Keep control telemetry (UDP) separate from video transport (WebRTC or another measured low-latency transport).
- Add packet sequence numbers and host timestamps before latency tuning.
- Never expose the UDP receiver directly to the public internet.
- A phone IMU provides orientation tracking; it does not by itself provide reliable 6DoF positional tracking.
