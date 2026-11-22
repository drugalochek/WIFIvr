"""Source-level regression checks for the OpenVR skeletal-hand bridge."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class OpenVrSkeletonContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.header = (ROOT / "SteamVRDriver/src/controller_device.h").read_text(encoding="utf-8")
        cls.driver = (ROOT / "SteamVRDriver/src/driver.cpp").read_text(encoding="utf-8")
        cls.windows = (ROOT / "Windows/VRoom/Program.cs").read_text(encoding="utf-8")
        cls.android = (ROOT / "Android/app/src/main/java/dev/vroom/client/HandSetupActivity.kt").read_text(encoding="utf-8")

    def test_standard_left_and_right_skeleton_paths(self):
        for path in ("/input/skeleton/left", "/input/skeleton/right",
                     "/skeleton/hand/left", "/skeleton/hand/right"):
            self.assertIn(path, self.header)
        self.assertIn("CreateSkeletonComponent", self.header)

    def test_31_bones_and_both_motion_ranges(self):
        self.assertIn("std::array<vr::VRBoneTransform_t, 31>", self.header)
        self.assertIn("VRSkeletalMotionRange_WithController", self.header)
        self.assertIn("VRSkeletalMotionRange_WithoutController", self.header)
        self.assertIn("UpdateSkeletonComponent", self.header)

    def test_openvr_finger_bone_indices(self):
        self.assertIn("{2, 6, 11, 16, 21}", self.header)
        self.assertIn("{1, 5, 9, 13, 17}", self.header)

    def test_no_trigger_or_grip_emulation(self):
        for symbol in ("CreateScalarComponent", "CreateBooleanComponent",
                       "UpdateScalarComponent", "UpdateBooleanComponent"):
            self.assertNotIn(symbol, self.header)

    def test_stale_hand_pose_is_cleared(self):
        self.assertIn("<= 300", self.header)
        self.assertIn("ClearSkeleton()", self.driver)
        self.assertIn("rootPositionHead", self.driver)
        self.assertIn("RunFrame(pose)", self.driver)

    def test_android_emits_smoothed_v2_world_landmarks(self):
        self.assertIn('.put("version", 2)', self.android)
        self.assertIn("worldLandmarks()", self.android)
        self.assertIn("smoothedWorldHands", self.android)
        self.assertIn('"rootPosition", rootPosition', self.android)

    def test_windows_accepts_and_relays_full_v2_packet(self):
        self.assertIn('case ("hand_pose", 2):', self.windows)
        self.assertIn("_handDriverRelay.SendAsync(packet.Buffer, packet.Buffer.Length", self.windows)
        self.assertIn("IPAddress.Loopback", self.windows)


if __name__ == "__main__":
    unittest.main()
