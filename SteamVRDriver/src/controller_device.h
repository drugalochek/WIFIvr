#pragma once

#include <openvr_driver.h>
#include <atomic>
#include <algorithm>
#include <cmath>

class VRoomController final : public vr::ITrackedDeviceServerDriver {
public:
    explicit VRoomController(vr::ETrackedControllerRole role) : role_(role) {}

    vr::EVRInitError Activate(uint32_t objectId) override {
        objectId_ = objectId;
        const auto container = vr::VRProperties()->TrackedDeviceToPropertyContainer(objectId);
        const bool isLeft = role_ == vr::TrackedControllerRole_LeftHand;
        const char* side = isLeft ? "Left" : "Right";
        const char* serial = isLeft ? "VRoom_Controller_Left" : "VRoom_Controller_Right";

        vr::VRProperties()->SetInt32Property(container, vr::Prop_ControllerRoleHint_Int32, role_);
        vr::VRProperties()->SetStringProperty(container, vr::Prop_TrackingSystemName_String, "VRoom");
        vr::VRProperties()->SetStringProperty(container, vr::Prop_ModelNumber_String, "VRoom Hand Controller");
        vr::VRProperties()->SetStringProperty(container, vr::Prop_SerialNumber_String, serial);
        vr::VRProperties()->SetStringProperty(container, vr::Prop_ManufacturerName_String, "VRoom");
        vr::VRProperties()->SetStringProperty(container, vr::Prop_ControllerType_String, "vroom_controller");
        vr::VRProperties()->SetStringProperty(
            container, vr::Prop_InputProfilePath_String,
            "{vroom}/resources/input/vroom_controller_profile.json");

        auto* input = vr::VRDriverInput();
        if (input->CreateScalarComponent(container, "/input/trigger/value", &triggerValue_,
                vr::VRScalarType_Absolute, vr::VRScalarUnits_NormalizedOneSided) != vr::VRInputError_None ||
            input->CreateBooleanComponent(container, "/input/trigger/click", &triggerClick_) != vr::VRInputError_None ||
            input->CreateScalarComponent(container, "/input/grip/value", &gripValue_,
                vr::VRScalarType_Absolute, vr::VRScalarUnits_NormalizedOneSided) != vr::VRInputError_None ||
            input->CreateBooleanComponent(container, "/input/grip/click", &gripClick_) != vr::VRInputError_None ||
            input->CreateHapticComponent(container, "/output/haptic", &haptic_) != vr::VRInputError_None) {
            vr::VRDriverLog()->Log("VRoom: failed to create controller input components");
            objectId_ = vr::k_unTrackedDeviceIndexInvalid;
            return vr::VRInitError_Driver_Unknown;
        }

        vr::VRDriverLog()->Log(isLeft
            ? "VRoom: left hand controller registered (2D pinch inputs; pose awaits calibration)"
            : "VRoom: right hand controller registered (2D pinch inputs; pose awaits calibration)");
        return vr::VRInitError_None;
    }

    void Deactivate() override { objectId_ = vr::k_unTrackedDeviceIndexInvalid; }
    void EnterStandby() override {}
    void* GetComponent(const char*) override { return nullptr; }
    void DebugRequest(const char*, char* response, uint32_t size) override {
        if (response && size) response[0] = '\0';
    }

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

    void SetPinch(float value) {
        if (!std::isfinite(value) || value < 0.0f) {
            tracked_.store(false, std::memory_order_release);
            pinch_.store(0.0f, std::memory_order_relaxed);
            return;
        }
        tracked_.store(true, std::memory_order_release);
        pinch_.store(std::clamp(value, 0.0f, 1.0f), std::memory_order_relaxed);
    }

    void RunFrame() {
        if (objectId_ == vr::k_unTrackedDeviceIndexInvalid) return;
        const bool tracked = tracked_.load(std::memory_order_acquire);
        const float trigger = tracked ? pinch_.load(std::memory_order_relaxed) : 0.0f;
        vr::VRDriverInput()->UpdateScalarComponent(triggerValue_, trigger, 0.0);
        vr::VRDriverInput()->UpdateBooleanComponent(triggerClick_, tracked && trigger >= 0.65f, 0.0);
        // Grip is deliberately not synthesized from 2D landmarks.
        vr::VRDriverInput()->UpdateScalarComponent(gripValue_, 0.0f, 0.0);
        vr::VRDriverInput()->UpdateBooleanComponent(gripClick_, false, 0.0);
        vr::VRServerDriverHost()->TrackedDevicePoseUpdated(objectId_, GetPose(), sizeof(vr::DriverPose_t));
    }

private:
    vr::ETrackedControllerRole role_;
    uint32_t objectId_ = vr::k_unTrackedDeviceIndexInvalid;
    vr::VRInputComponentHandle_t triggerValue_ = 0;
    vr::VRInputComponentHandle_t triggerClick_ = 0;
    vr::VRInputComponentHandle_t gripValue_ = 0;
    vr::VRInputComponentHandle_t gripClick_ = 0;
    vr::VRInputComponentHandle_t haptic_ = 0;
    std::atomic<bool> tracked_{false};
    std::atomic<float> pinch_{0.0f};
};
