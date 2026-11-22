"""Source contract checks for the unified Android VR session."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SESSION = ROOT / "Android/app/src/main/java/dev/vroom/client/VrSessionActivity.kt"
MAIN = ROOT / "Android/app/src/main/java/dev/vroom/client/MainActivity.kt"
MANIFEST = ROOT / "Android/app/src/main/AndroidManifest.xml"


class UnifiedVrSessionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.session = SESSION.read_text(encoding="utf-8")
        cls.main = MAIN.read_text(encoding="utf-8")
        cls.manifest = MANIFEST.read_text(encoding="utf-8")

    def test_one_activity_owns_head_hands_and_display(self):
        for token in ("SensorEventListener", "HandLandmarker", "receiveDisplay",
                      "startHeadSender", "sendHandPose", "startCamera"):
            self.assertIn(token, self.session)

    def test_uses_existing_wire_ports(self):
        for token in ("9000", "9001", "9002"):
            self.assertIn(token, self.session)

    def test_main_screen_launches_unified_session(self):
        self.assertIn("VrSessionActivity::class.java", self.main)
        self.assertIn("Запустить VR-сеанс", self.main)

    def test_activity_is_registered_and_landscape(self):
        self.assertIn('android:name=".VrSessionActivity"', self.manifest)
        self.assertIn('android:screenOrientation="landscape"', self.manifest)


if __name__ == "__main__":
    unittest.main()
