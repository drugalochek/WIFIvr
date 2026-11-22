#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <openvr_driver.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <regex>
#include <string>
#include <thread>

namespace {
class VRoomProvider;

class VRoomHmd final : public vr::ITrackedDeviceServerDriver,
                       public vr::IVRDisplayComponent,
                       public vr::IVRVirtualDisplay {
public:
    explicit VRoomHmd(VRoomProvider* provider) : provider_(provider) {}

    vr::EVRInitError Activate(uint32_t objectId) override;
    void Deactivate() override { objectId_ = vr::k_unTrackedDeviceIndexInvalid; }
    void EnterStandby() override {}
    void* GetComponent(const char* name) override {
        if (!name) return nullptr;
        if (std::strcmp(name, vr::IVRDisplayComponent_Version) == 0)
            return static_cast<vr::IVRDisplayComponent*>(this);
        if (std::strcmp(name, vr::IVRVirtualDisplay_Version) == 0)
            return static_cast<vr::IVRVirtualDisplay*>(this);
        return nullptr;
    }
    void DebugRequest(const char*, char* response, uint32_t size) override {
        if (response && size) response[0] = '\0';
    }
    vr::DriverPose_t GetPose() override;

    void GetWindowBounds(int32_t* x, int32_t* y, uint32_t* width, uint32_t* height) override {
        if (x) *x = 0; if (y) *y = 0;
        if (width) *width = 2160; if (height) *height = 1200;
    }
    bool IsDisplayOnDesktop() override { return false; }
    bool IsDisplayRealDisplay() override { return false; }
    void GetRecommendedRenderTargetSize(uint32_t* width, uint32_t* height) override {
        if (width) *width = 2160; if (height) *height = 1200;
    }
    void GetEyeOutputViewport(vr::EVREye eye, uint32_t* x, uint32_t* y,
                              uint32_t* width, uint32_t* height) override {
        if (x) *x = eye == vr::Eye_Left ? 0u : 1080u;
        if (y) *y = 0;
        if (width) *width = 1080;
        if (height) *height = 1200;
    }
    void GetProjectionRaw(vr::EVREye, float* left, float* right,
                          float* top, float* bottom) override {
        if (left) *left = -1.0f; if (right) *right = 1.0f;
        if (top) *top = -1.0f; if (bottom) *bottom = 1.0f;
    }
    vr::DistortionCoordinates_t ComputeDistortion(vr::EVREye, float u, float v) override {
        vr::DistortionCoordinates_t result{};
        result.rfRed[0] = result.rfGreen[0] = result.rfBlue[0] = u;
        result.rfRed[1] = result.rfGreen[1] = result.rfBlue[1] = v;
        return result;
    }
    bool ComputeInverseDistortion(vr::HmdVector2_t* result, vr::EVREye,
                                  uint32_t, float u, float v) override {
        if (!result) return false;
        result->v[0] = u; result->v[1] = v; return true;
    }

    void Present(const vr::PresentInfo_t*, uint32_t) override {
        // The compositor now has a virtual-display endpoint. Frame transport to Android
        // is the next integration step; no texture is copied or streamed in this version.
        presentedFrames_.fetch_add(1, std::memory_order_relaxed);
    }
    void WaitForPresent() override {}
    bool GetTimeSinceLastVsync(float* seconds, uint64_t* frameCounter) override {
        if (seconds) *seconds = 0.0f;
        if (frameCounter) *frameCounter = presentedFrames_.load(std::memory_order_relaxed);
        return false;
    }

    uint32_t ObjectId() const { return objectId_; }

private:
    VRoomProvider* provider_;
    uint32_t objectId_ = vr::k_unTrackedDeviceIndexInvalid;
    std::atomic<uint64_t> presentedFrames_{0};
};

class VRoomProvider final : public vr::IServerTrackedDeviceProvider {
public:
    vr::EVRInitError Init(vr::IVRDriverContext* context) override {
        VR_INIT_SERVER_DRIVER_CONTEXT(context);
        WSADATA data{};
        if (WSAStartup(MAKEWORD(2, 2), &data) != 0) {
            vr::VRDriverLog()->Log("VRoom: WSAStartup failed");
            return vr::VRInitError_Driver_Failed;
        }
        winsockReady_ = true;
        socket_ = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (socket_ != INVALID_SOCKET) {
            DWORD timeout = 250;
            setsockopt(socket_, SOL_SOCKET, SO_RCVTIMEO,
                       reinterpret_cast<const char*>(&timeout), sizeof(timeout));
            sockaddr_in address{};
            address.sin_family = AF_INET;
            address.sin_port = htons(9003);
            address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            if (::bind(socket_, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
                closesocket(socket_); socket_ = INVALID_SOCKET;
                vr::VRDriverLog()->Log("VRoom: could not bind local pose relay on 127.0.0.1:9003");
            } else {
                receiver_ = std::thread([this] { ReceiveLoop(); });
            }
        }
        hmd_ = std::make_unique<VRoomHmd>(this);
        if (!vr::VRServerDriverHost()->TrackedDeviceAdded(
                "VRoom_HMD_001", vr::TrackedDeviceClass_HMD, hmd_.get())) {
            vr::VRDriverLog()->Log("VRoom: SteamVR rejected HMD registration");
            return vr::VRInitError_Driver_Unknown;
        }
        vr::VRDriverLog()->Log("VRoom: virtual HMD registered; waiting for phone IMU on local UDP 9003");
        return vr::VRInitError_None;
    }

    void Cleanup() override {
        stopping_.store(true);
        if (socket_ != INVALID_SOCKET) {
            closesocket(socket_);
            socket_ = INVALID_SOCKET;
        }
        if (receiver_.joinable()) receiver_.join();
        hmd_.reset();
        if (winsockReady_) { WSACleanup(); winsockReady_ = false; }
        VR_CLEANUP_SERVER_DRIVER_CONTEXT();
    }
    const char* const* GetInterfaceVersions() override { return vr::k_InterfaceVersions; }
    void RunFrame() override {
        if (!hmd_ || hmd_->ObjectId() == vr::k_unTrackedDeviceIndexInvalid) return;
        const auto pose = MakePose();
        vr::VRServerDriverHost()->TrackedDevicePoseUpdated(
            hmd_->ObjectId(), pose, sizeof(vr::DriverPose_t));
    }
    bool ShouldBlockStandbyMode() override { return false; }
    void EnterStandby() override {}
    void LeaveStandby() override {}

    vr::DriverPose_t MakePose() const {
        vr::DriverPose_t pose{};
        pose.qWorldFromDriverRotation = {1.0, 0.0, 0.0, 0.0};
        pose.qDriverFromHeadRotation = {1.0, 0.0, 0.0, 0.0};
        pose.qRotation = {
            1.0,
            qx_.load(std::memory_order_relaxed),
            qy_.load(std::memory_order_relaxed),
            qz_.load(std::memory_order_relaxed)
        };
        pose.result = hasPose_.load(std::memory_order_acquire)
            ? vr::TrackingResult_Running_OK : vr::TrackingResult_Uninitialized;
        pose.poseIsValid = hasPose_.load(std::memory_order_acquire);
        pose.deviceIsConnected = true;
        pose.willDriftInYaw = true;
        pose.shouldApplyHeadModel = false;
        return pose;
    }

private:
    void ReceiveLoop() {
        static const std::regex pattern(
            R"("orientation"\s*:\s*\{\s*"x"\s*:\s*([-+0-9.eE]+)\s*,\s*"y"\s*:\s*([-+0-9.eE]+)\s*,\s*"z"\s*:\s*([-+0-9.eE]+)\s*,\s*"w"\s*:\s*([-+0-9.eE]+))");
        char buffer[2048];
        while (!stopping_.load()) {
            const int count = recvfrom(socket_, buffer, sizeof(buffer) - 1, 0, nullptr, nullptr);
            if (count <= 0) continue;
            buffer[count] = '\0';
            std::cmatch match;
            if (!std::regex_search(buffer, match, pattern)) continue;
            try {
                const double x = std::stod(match[1].str());
                const double y = std::stod(match[2].str());
                const double z = std::stod(match[3].str());
                const double w = std::stod(match[4].str());
                const double norm = std::sqrt(x*x + y*y + z*z + w*w);
                if (!std::isfinite(norm) || norm < 0.5 || norm > 1.5) continue;
                qx_.store(x / norm, std::memory_order_relaxed);
                qy_.store(y / norm, std::memory_order_relaxed);
                qz_.store(z / norm, std::memory_order_relaxed);
                qw_.store(w / norm, std::memory_order_relaxed);
                hasPose_.store(true, std::memory_order_release);
            } catch (...) { }
        }
    }

    std::unique_ptr<VRoomHmd> hmd_;
    SOCKET socket_ = INVALID_SOCKET;
    std::thread receiver_;
    std::atomic<bool> stopping_{false};
    std::atomic<bool> hasPose_{false};
    std::atomic<double> qx_{0}, qy_{0}, qz_{0}, qw_{1};
    bool winsockReady_ = false;
};

vr::EVRInitError VRoomHmd::Activate(uint32_t objectId) {
    objectId_ = objectId;
    auto* properties = vr::VRProperties();
    const auto container = properties->TrackedDeviceToPropertyContainer(objectId);
    properties->SetStringProperty(container, vr::Prop_TrackingSystemName_String, "VRoom");
    properties->SetStringProperty(container, vr::Prop_ModelNumber_String, "VRoom Wireless HMD");
    properties->SetStringProperty(container, vr::Prop_SerialNumber_String, "VRoom_HMD_001");
    properties->SetStringProperty(container, vr::Prop_ManufacturerName_String, "VRoom");
    properties->SetStringProperty(container, vr::Prop_RegisteredDeviceType_String, "vroom/headset");
    properties->SetFloatProperty(container, vr::Prop_DisplayFrequency_Float, 72.0f);
    properties->SetFloatProperty(container, vr::Prop_UserIpdMeters_Float, 0.064f);
    properties->SetFloatProperty(container, vr::Prop_SecondsFromVsyncToPhotons_Float, 0.011f);
    properties->SetBoolProperty(container, vr::Prop_ReportsTimeSinceVSync_Bool, false);
    properties->SetBoolProperty(container, vr::Prop_IsOnDesktop_Bool, false);
    vr::VRDriverLog()->Log("VRoom: HMD activated");
    return vr::VRInitError_None;
}

vr::DriverPose_t VRoomHmd::GetPose() { return provider_->MakePose(); }

VRoomProvider g_provider;
} // namespace

VR_INTERFACE void* HmdDriverFactory(const char* interfaceName, int* returnCode) {
    if (interfaceName && std::strcmp(interfaceName, vr::IServerTrackedDeviceProvider_Version) == 0) {
        if (returnCode) *returnCode = vr::VRInitError_None;
        return &g_provider;
    }
    if (returnCode) *returnCode = vr::VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
