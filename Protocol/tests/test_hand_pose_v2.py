"""Dependency-free contract checks for the hand-pose v2 wire format."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class HandPoseV2ContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = json.loads((ROOT / "hand-pose.v2.schema.json").read_text(encoding="utf-8"))

    def test_schema_declares_version_and_required_hand_data(self):
        self.assertEqual(self.schema["properties"]["version"]["const"], 2)
        hand = self.schema["properties"]["hands"]["items"]
        self.assertEqual(set(hand["required"]), {
            "handedness", "confidence", "gesture", "pinch", "landmarks", "worldLandmarks", "rootPosition"
        })

    def test_image_and_world_landmarks_are_21_points(self):
        defs = self.schema["$defs"]
        self.assertEqual(defs["imageLandmarks"]["minItems"], 21)
        self.assertEqual(defs["imageLandmarks"]["maxItems"], 21)
        self.assertEqual(defs["worldLandmarks"]["minItems"], 21)
        self.assertEqual(defs["worldLandmarks"]["maxItems"], 21)

    def test_world_coordinates_are_not_clamped_to_image_range(self):
        defs = self.schema["$defs"]
        world_xyz = defs["worldLandmarks"]["items"]["properties"]
        image_xyz = defs["imageLandmarks"]["items"]["properties"]
        self.assertNotIn("minimum", world_xyz["x"])
        self.assertEqual(image_xyz["x"]["minimum"], 0)
        self.assertEqual(image_xyz["x"]["maximum"], 1)

    def test_root_position_is_hmd_local_and_bounded(self):
        root = self.schema["properties"]["hands"]["items"]["properties"]["rootPosition"]
        self.assertEqual(set(root["required"]), {"x", "y", "z"})
        self.assertEqual(root["properties"]["x"]["minimum"], -3)
        self.assertIn("HMD-local", root["description"])

    def test_confidence_and_pinch_are_normalized(self):
        props = self.schema["properties"]["hands"]["items"]["properties"]
        for key in ("confidence", "pinch"):
            self.assertEqual(props[key]["minimum"], 0)
            self.assertEqual(props[key]["maximum"], 1)


if __name__ == "__main__":
    unittest.main()
