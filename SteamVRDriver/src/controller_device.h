#pragma once

#include <openvr_driver.h>
#include <array>
#include <atomic>
#include <cmath>
#include <chrono>
#include <cstdint>
#include <mutex>

class VRoomController final : public vr::ITrackedDeviceServerDriver {
public:
    explicit VRoomController(vr::ETrackedControllerRole role) : role_(role) {}

    vr::EVRInitError Activate(uint32_t objectId) override {
        objectId_ = objectId;
        const auto container = vr::VRProperties()->TrackedDeviceToPropertyContainer(objectId);
        const bool isLeft = role_ == vr::TrackedControllerRole_LeftHand;
        const char* serial = isLeft ? "VRoom_Controller_Left" : "VRoom_Controller_Right";

        vr::VRProperties()->SetInt32Property(container, vr::Prop_ControllerRoleHint_Int32, role_);
        vr::VRProperties()->SetStringProperty(container, vr::Prop_TrackingSystemName_String, "VRoom");
        vr::VRProperties()->SetStringProperty(container, vr::Prop_ModelNumber_String, "VRoom Skeletal Hand");
        vr::VRProperties()->SetStringProperty(container, vr::Prop_SerialNumber_String, serial);
        vr::VRProperties()->SetStringProperty(container, vr::Prop_ManufacturerName_String, "VRoom");
        vr::VRProperties()->SetStringProperty(container, vr::Prop_ControllerType_String, "vroom_hand");
        vr::VRProperties()->SetStringProperty(container, vr::Prop_InputProfilePath_String,
            "{vroom}/resources/input/vroom_controller_profile.json");

        auto* input = vr::VRDriverInput();
        if (input->CreateSkeletonComponent(container,
                isLeft ? "/input/skeleton/left" : "/input/skeleton/right",
                isLeft ? "/skeleton/hand/left" : "/skeleton/hand/right",
                "/pose/raw", vr::VRSkeletalTracking_Partial, nullptr, 0, &skeleton_) != vr::VRInputError_None) {
            vr::VRDriverLog()->Log("VRoom: failed to create OpenVR skeletal hand component");
            objectId_ = vr::k_unTrackedDeviceIndexInvalid;
            return vr::VRInitError_Driver_Unknown;
        }

        vr::VRDriverLog()->Log(isLeft
            ? "VRoom: left skeletal hand registered; no trigger/grip emulation; room-space pose awaits camera calibration"
            : "VRoom: right skeletal hand registered; no trigger/grip emulation; room-space pose awaits camera calibration");
        return vr::VRInitError_None;
    }

    void Deactivate() override { objectId_ = vr::k_unTrackedDeviceIndexInvalid; }
    void EnterStandby() override {}
    void* GetComponent(const char*) override { return nullptr; }
    void DebugRequest(const char*, char* response, uint32_t size) override {
        if (response && size) response[0] = '\0';
    }

    // MediaPipe world landmarks are hand-centred; they cannot establish room-space position.
    // Keep the tracked-device pose invalid until camera-to-HMD calibration exists.
    vr::DriverPose_t GetPose() override {
        vr::DriverPose_t pose{};
        pose.qWorldFromDriverRotation = {1.0, 0.0, 0.0, 0.0};
        pose.qDriverFromHeadRotation = {1.0, 0.0, 0.0, 0.0};
        pose.qRotation = {1.0, 0.0, 0.0, 0.0};
        pose.result = vr::TrackingResult_Uninitialized;
        pose.poseIsValid = false;
        pose.deviceIsConnected = true;
        return pose;
    }

    void SetHandPose(const std::array<std::array<float, 3>, 21>& points, float confidence) {
        if (!std::isfinite(confidence) || confidence < 0.35f) {
            ClearSkeleton();
            return;
        }
        for (const auto& p : points) {
            if (!std::isfinite(p[0]) || !std::isfinite(p[1]) || !std::isfinite(p[2])) {
                ClearSkeleton();
                return;
            }
        }

        std::array<vr::VRBoneTransform_t, 31> bones{};
        for (auto& bone : bones) {
            bone.position = {0.0f, 0.0f, 0.0f, 1.0f};
            bone.orientation = {1.0f, 0.0f, 0.0f, 0.0f};
        }

        // OpenVR: root, wrist, thumb[4], four five-bone fingers, five auxiliary bones.
        // MediaPipe: wrist 0; thumb 1..4; index 5..8; middle 9..12; ring 13..16; little 17..20.
        const int boneStarts[5] = {2, 6, 11, 16, 21};
        const int landmarkStarts[5] = {1, 5, 9, 13, 17};
        for (int finger = 0; finger < 5; ++finger) {
            Quat parentGlobal{};
            int parentLandmark = 0;
            for (int segment = 0; segment < 4; ++segment) {
                const int boneIndex = boneStarts[finger] + segment;
                const int landmarkIndex = landmarkStarts[finger] + segment;
                const Vec3 parent = ToOpenVr(points[parentLandmark]);
                const Vec3 joint = ToOpenVr(points[landmarkIndex]);
                const Vec3 delta = Subtract(joint, parent);
                const Quat globalOrientation = RotationFromPositiveX(delta);
                const Quat inverseParent = Conjugate(parentGlobal);
                const Vec3 localPosition = Rotate(inverseParent, delta);
                const Quat localOrientation = Multiply(inverseParent, globalOrientation);
                bones[boneIndex].position = {localPosition.x, localPosition.y, localPosition.z, 1.0f};
                bones[boneIndex].orientation = {
                    localOrientation.w, localOrientation.x, localOrientation.y, localOrientation.z
                };
                parentGlobal = globalOrientation;
                parentLandmark = landmarkIndex;
            }
            if (finger > 0) {
                const int tipBone = boneStarts[finger] + 4;
                bones[tipBone].position = {0.0f, 0.0f, 0.0f, 1.0f};
                bones[tipBone].orientation = {1.0f, 0.0f, 0.0f, 0.0f};
            }
        }

        {
            std::lock_guard<std::mutex> lock(skeletonMutex_);
            skeletonTransforms_ = bones;
        }
        hasSkeleton_.store(true, std::memory_order_release);
        lastUpdateMs_.store(NowMs(), std::memory_order_release);
    }

    void ClearSkeleton() {
        hasSkeleton_.store(false, std::memory_order_release);
        lastUpdateMs_.store(NowMs(), std::memory_order_release);
    }

    void RunFrame() {
        if (objectId_ == vr::k_unTrackedDeviceIndexInvalid) return;
        const auto now = NowMs();
        const bool fresh = hasSkeleton_.load(std::memory_order_acquire) &&
            now - lastUpdateMs_.load(std::memory_order_acquire) <= 300;
        if (!fresh) hasSkeleton_.store(false, std::memory_order_release);

        std::array<vr::VRBoneTransform_t, 31> snapshot{};
        if (fresh) {
            std::lock_guard<std::mutex> lock(skeletonMutex_);
            snapshot = skeletonTransforms_;
        } else {
            for (auto& bone : snapshot) {
                bone.position = {0.0f, 0.0f, 0.0f, 1.0f};
                bone.orientation = {1.0f, 0.0f, 0.0f, 0.0f};
            }
        }
        auto* input = vr::VRDriverInput();
        input->UpdateSkeletonComponent(skeleton_, vr::VRSkeletalMotionRange_WithController,
            snapshot.data(), static_cast<uint32_t>(snapshot.size()));
        input->UpdateSkeletonComponent(skeleton_, vr::VRSkeletalMotionRange_WithoutController,
            snapshot.data(), static_cast<uint32_t>(snapshot.size()));
        vr::VRServerDriverHost()->TrackedDevicePoseUpdated(objectId_, GetPose(), sizeof(vr::DriverPose_t));
    }

private:
    struct Vec3 { float x, y, z; };
    struct Quat { float w = 1.0f, x = 0.0f, y = 0.0f, z = 0.0f; };

    Vec3 ToOpenVr(const std::array<float, 3>& point) const {
        const float handedX = role_ == vr::TrackedControllerRole_RightHand ? -point[0] : point[0];
        return {handedX, -point[1], -point[2]};
    }
    static Vec3 Subtract(const Vec3& a, const Vec3& b) {
        return {a.x - b.x, a.y - b.y, a.z - b.z};
    }
    static Quat Conjugate(const Quat& q) { return {q.w, -q.x, -q.y, -q.z}; }
    static Quat Multiply(const Quat& a, const Quat& b) {
        return {
            a.w*b.w - a.x*b.x - a.y*b.y - a.z*b.z,
            a.w*b.x + a.x*b.w + a.y*b.z - a.z*b.y,
            a.w*b.y - a.x*b.z + a.y*b.w + a.z*b.x,
            a.w*b.z + a.x*b.y - a.y*b.x + a.z*b.w
        };
    }
    static Vec3 Rotate(const Quat& q, const Vec3& v) {
        const Quat p{0.0f, v.x, v.y, v.z};
        const Quat r = Multiply(Multiply(q, p), Conjugate(q));
        return {r.x, r.y, r.z};
    }
    // Valve's sample skeletal driver uses +X as the local segment axis.
    static Quat RotationFromPositiveX(const Vec3& direction) {
        const float length = std::sqrt(direction.x*direction.x + direction.y*direction.y + direction.z*direction.z);
        if (!std::isfinite(length) || length < 1e-6f) return {};
        const float x = direction.x / length;
        const float y = direction.y / length;
        const float z = direction.z / length;
        if (x < -0.999999f) return {0.0f, 0.0f, 1.0f, 0.0f};
        const float scale = std::sqrt(2.0f * (1.0f + x));
        return {scale * 0.5f, 0.0f, -z / scale, y / scale};
    }
    static int64_t NowMs() {
        using namespace std::chrono;
        return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
    }

    vr::ETrackedControllerRole role_;
    uint32_t objectId_ = vr::k_unTrackedDeviceIndexInvalid;
    vr::VRInputComponentHandle_t skeleton_ = 0;
    std::atomic<bool> hasSkeleton_{false};
    std::atomic<int64_t> lastUpdateMs_{0};
    std::mutex skeletonMutex_;
    std::array<vr::VRBoneTransform_t, 31> skeletonTransforms_{};
};
