# VRoom hardware validation plan

This checklist records observable results. Do not mark a stage complete from a successful compile alone.

## 1. Windows host and Android connection
- [ ] Start VRoom Desktop and confirm UDP 9000 and TCP 9001/9002 start without errors.
- [ ] Connect the Android app to the PC's private IPv4 address.
- [ ] Confirm the Windows phone-camera panel receives frames.
- [ ] Confirm hand landmarks move with the hands and the displayed handedness is correct.
- [ ] Leave the connection running for 10 minutes and note disconnects or freezes.

## 2. PC webcam body tracking
- [ ] Select the intended PC webcam in the Windows app.
- [ ] Confirm MoveNet downloads once and subsequent starts use the cached model.
- [ ] Stand fully in frame and verify all visible joints follow movement.
- [ ] Test low light, partial occlusion, and a second person entering the frame.
- [ ] Record average confidence and whether the skeleton flickers or jumps.
- [ ] Confirm the PC webcam is the body-pose source; the phone camera remains the hand-tracking source.

## 3. SteamVR head tracking
- [ ] Register the VRoom driver with SteamVR.
- [ ] Confirm SteamVR detects VRoom_HMD_001 as the active HMD.
- [ ] Start the unified Android **VR-сеанс** and confirm the phone quaternion reaches the Windows host and driver while camera tracking remains active.
- [ ] Rotate the phone slowly on each axis and verify the virtual head orientation follows without axis inversion.
- [ ] Check neutral orientation and drift over several minutes.
- [ ] Repeat SteamVR startup and Room Setup; record any chaperone or universe errors.

## 4. PC-to-phone display
- [ ] Start the Android VR display activity with the correct PC address.
- [ ] Confirm the driver logs a connection on loopback TCP 9004.
- [ ] Confirm Windows reports the phone connected on TCP 9002.
- [ ] Confirm the phone receives changing frames from the SteamVR compositor.
- [ ] Verify left and right eye images are distinct and correctly ordered.
- [ ] Record frame rate, visible stutter, freezes, and end-to-end delay.
- [ ] Test reconnect after closing and reopening the Android display activity.

## 5. OpenVR skeletal hands, 3D tracking, locomotion and overlay
The driver currently registers controller-class hand endpoints only to expose OpenVR skeletal-input components. It does not provide valid tracked-device poses or trigger/grip button emulation. Do not treat hand-centred MediaPipe landmarks as room-space tracking.
- [ ] Verify left/right skeletal input components appear in SteamVR.
- [ ] Confirm both 31-bone motion ranges update and the hand mesh follows each finger.
- [ ] Confirm the hand device poses follow wrist movement relative to the HMD. The current root uses a nominal 85 mm palm width and assumed camera focal scale; record actual position error before treating it as calibrated.
- [ ] Confirm missing hands and UDP loss clear the previous skeletal pose within 300 ms.
- [ ] Verify left/right bone mapping and axis orientation in a dedicated skeletal-input test scene.
- [ ] Measure the user's palm breadth and calibrate camera intrinsics/focal scale.
- [ ] Calibrate camera-to-HMD offset/orientation and wrist/root placement in the VR room.
- [ ] Validate tracking volume and calibration repeatability.
- [ ] Test locomotion in a controlled scene with an immediate disable path.
- [ ] Verify overlay anchoring and desktop interaction in headset.

## Report format
For each failed check, capture:
- Windows app log around the failure.
- SteamVR driver log around the same timestamp.
- Android status text.
- PC and phone model, webcam model, connection type (Ethernet/Wi-Fi), and approximate distance from router.
- Exact steps that reproduce the failure.
