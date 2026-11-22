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
- [ ] Confirm the phone quaternion reaches the Windows host and driver.
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

## 5. Controllers, 3D tracking, locomotion and overlay
These stages are blocked until controller devices, calibration, and validated tracking data are implemented. Do not treat the current 2D skeleton preview as a substitute.
- [ ] Verify two controller devices appear in SteamVR.
- [ ] Verify controller poses and buttons in a controller test scene.
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
