#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <d3d11.h>
#include <wincodec.h>
#include <wrl/client.h>
#include <objbase.h>
#include <oleauto.h>
#include <openvr_driver.h>
#include "controller_device.h"
#include <atomic>
#include <chrono>
#include <ctime>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <vector>
#include <mutex>
#include <condition_variable>
#include <algorithm>
#include <regex>
#include <string>
#include <thread>

namespace {
constexpr uint64_t kVRoomUniverseId = 0x56524F4FULL;

// VRoom currently has rotational IMU tracking but no positional tracking.
// Supply a deterministic standing universe with no fabricated room boundary.
std::string BuildChaperoneJson() {
    const std::time_t now = std::time(nullptr);
    std::tm utc{};
    gmtime_s(&utc, &now);
    char timestamp[32]{};
    std::strftime(timestamp, sizeof(timestamp), "%Y-%m-%dT%H:%M:%SZ", &utc);

    return std::string("{") +
        "\"json_id\":\"chaperone_info\"," +
        "\"version\":5," +
        "\"time\":\"" + timestamp + "\"," +
        "\"universes\":[{" +
            "\"collision_bounds\":[]," +
            "\"play_area\":[0.0,0.0]," +
            "\"seated\":{\"translation\":[0.0,0.0,0.0],\"yaw\":0.0}," +
            "\"standing\":{\"translation\":[0.0,0.0,0.0],\"yaw\":0.0}," +
            "\"universeID\":" + std::to_string(kVRoomUniverseId) +
        "}]}";
}

class VRoomProvider;

class VRoomHmd final : public vr::ITrackedDeviceServerDriver,
                       public vr::IVRDisplayComponent {
public:
    explicit VRoomHmd(VRoomProvider* provider) : provider_(provider) {}

    vr::EVRInitError Activate(uint32_t objectId) override;
    void Deactivate() override { objectId_ = vr::k_unTrackedDeviceIndexInvalid; }
    void EnterStandby() override {}
    void* GetComponent(const char* name) override {
        if (!name) return nullptr;
        if (std::strcmp(name, vr::IVRDisplayComponent_Version) == 0)
            return static_cast<vr::IVRDisplayComponent*>(this);
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

    uint32_t ObjectId() const { return objectId_; }

private:
    VRoomProvider* provider_;
    uint32_t objectId_ = vr::k_unTrackedDeviceIndexInvalid;
};

// OpenVR's IVRVirtualDisplay belongs to a DisplayRedirect device, not the HMD.
// Keep tracking/display geometry on VRoomHmd and route the compositor's final
// image through this separate redirect device.
class VRoomDisplayRedirect final : public vr::ITrackedDeviceServerDriver,
                                   public vr::IVRVirtualDisplay {
public:
    explicit VRoomDisplayRedirect(VRoomProvider* provider) : provider_(provider) {}

    vr::EVRInitError Activate(uint32_t objectId) override;
    void Deactivate() override { objectId_ = vr::k_unTrackedDeviceIndexInvalid; }
    void EnterStandby() override {}
    void* GetComponent(const char* name) override {
        if (name && std::strcmp(name, vr::IVRVirtualDisplay_Version) == 0)
            return static_cast<vr::IVRVirtualDisplay*>(this);
        return nullptr;
    }
    void DebugRequest(const char*, char* response, uint32_t size) override {
        if (response && size) response[0] = '\0';
    }
    vr::DriverPose_t GetPose() override;

    void Present(const vr::PresentInfo_t* info, uint32_t size) override;
    void WaitForPresent() override;
    bool GetTimeSinceLastVsync(float* seconds, uint64_t* frameCounter) override;

private:
    VRoomProvider* provider_;
    uint32_t objectId_ = vr::k_unTrackedDeviceIndexInvalid;
    std::mutex presentMutex_;
    vr::SharedTextureHandle_t pendingHandle_ = INVALID_SHARED_TEXTURE_HANDLE;
    bool hasPendingFrame_ = false;
    std::chrono::steady_clock::time_point lastVsync_ = std::chrono::steady_clock::now();
    uint64_t vsyncCounter_ = 0;
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
        const HRESULT deviceResult = D3D11CreateDevice(
            nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            nullptr, 0, D3D11_SDK_VERSION, &d3dDevice_, nullptr, &d3dContext_);
        if (FAILED(deviceResult)) {
            vr::VRDriverLog()->Log("VRoom: could not create D3D11 capture device");
        }
        frameListenSocket_ = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (frameListenSocket_ != INVALID_SOCKET) {
            sockaddr_in frameAddress{};
            frameAddress.sin_family = AF_INET;
            frameAddress.sin_port = htons(9004);
            frameAddress.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            if (::bind(frameListenSocket_, reinterpret_cast<sockaddr*>(&frameAddress), sizeof(frameAddress)) != 0 ||
                ::listen(frameListenSocket_, 1) != 0) {
                closesocket(frameListenSocket_);
                frameListenSocket_ = INVALID_SOCKET;
                vr::VRDriverLog()->Log("VRoom: could not listen for local video relay on 127.0.0.1:9004");
            } else {
                frameThread_ = std::thread([this] { FrameServerLoop(); });
                vr::VRDriverLog()->Log("VRoom: OpenVR frame relay listening on 127.0.0.1:9004");
            }
        }
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

        leftController_ = std::make_unique<VRoomController>(vr::TrackedControllerRole_LeftHand);
        rightController_ = std::make_unique<VRoomController>(vr::TrackedControllerRole_RightHand);
        if (!vr::VRServerDriverHost()->TrackedDeviceAdded(
                "VRoom_Controller_Left", vr::TrackedDeviceClass_Controller, leftController_.get()) ||
            !vr::VRServerDriverHost()->TrackedDeviceAdded(
                "VRoom_Controller_Right", vr::TrackedDeviceClass_Controller, rightController_.get())) {
            vr::VRDriverLog()->Log("VRoom: SteamVR rejected one or both controller registrations");
            return vr::VRInitError_Driver_Unknown;
        }

        handSocket_ = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (handSocket_ != INVALID_SOCKET) {
            DWORD timeout = 250;
            setsockopt(handSocket_, SOL_SOCKET, SO_RCVTIMEO,
                       reinterpret_cast<const char*>(&timeout), sizeof(timeout));
            sockaddr_in handAddress{};
            handAddress.sin_family = AF_INET;
            handAddress.sin_port = htons(9005);
            handAddress.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            if (::bind(handSocket_, reinterpret_cast<sockaddr*>(&handAddress), sizeof(handAddress)) != 0) {
                closesocket(handSocket_);
                handSocket_ = INVALID_SOCKET;
                vr::VRDriverLog()->Log("VRoom: could not bind local hand-input relay on 127.0.0.1:9005");
            } else {
                handReceiver_ = std::thread([this] { ReceiveHandInputLoop(); });
                vr::VRDriverLog()->Log("VRoom: hand-input relay listening on 127.0.0.1:9005");
            }
        }

        displayRedirect_ = std::make_unique<VRoomDisplayRedirect>(this);
        if (!vr::VRServerDriverHost()->TrackedDeviceAdded(
                "VRoom_Display_001", vr::TrackedDeviceClass_DisplayRedirect, displayRedirect_.get())) {
            vr::VRDriverLog()->Log("VRoom: SteamVR rejected display redirect registration");
            return vr::VRInitError_Driver_Unknown;
        }
        vr::VRDriverLog()->Log("VRoom: virtual HMD and display redirect registered");
        return vr::VRInitError_None;
    }

    void Cleanup() override {
        stopping_.store(true);
        frameCondition_.notify_all();
        if (frameListenSocket_ != INVALID_SOCKET) {
            closesocket(frameListenSocket_);
            frameListenSocket_ = INVALID_SOCKET;
        }
        if (socket_ != INVALID_SOCKET) {
            closesocket(socket_);
            socket_ = INVALID_SOCKET;
        }
        if (handSocket_ != INVALID_SOCKET) {
            closesocket(handSocket_);
            handSocket_ = INVALID_SOCKET;
        }
        if (receiver_.joinable()) receiver_.join();
        if (handReceiver_.joinable()) handReceiver_.join();
        if (frameThread_.joinable()) frameThread_.join();
        displayRedirect_.reset();
        hmd_.reset();
        d3dContext_.Reset();
        d3dDevice_.Reset();
        if (winsockReady_) { WSACleanup(); winsockReady_ = false; }
        VR_CLEANUP_SERVER_DRIVER_CONTEXT();
    }
    const char* const* GetInterfaceVersions() override { return vr::k_InterfaceVersions; }
    void RunFrame() override {
        const auto pose = MakePose();
        if (hmd_ && hmd_->ObjectId() != vr::k_unTrackedDeviceIndexInvalid) {
            vr::VRServerDriverHost()->TrackedDevicePoseUpdated(
                hmd_->ObjectId(), pose, sizeof(vr::DriverPose_t));
        }
        if (leftController_) leftController_->RunFrame(pose);
        if (rightController_) rightController_->RunFrame(pose);
    }
    bool ShouldBlockStandbyMode() override { return false; }
    void EnterStandby() override {}
    void LeaveStandby() override {}

    vr::DriverPose_t MakePose() const {
        vr::DriverPose_t pose{};
        pose.qWorldFromDriverRotation = {1.0, 0.0, 0.0, 0.0};
        pose.qDriverFromHeadRotation = {1.0, 0.0, 0.0, 0.0};
        pose.qRotation = {
            qw_.load(std::memory_order_relaxed),
            qx_.load(std::memory_order_relaxed),
            qy_.load(std::memory_order_relaxed),
            qz_.load(std::memory_order_relaxed)
        };
        // IMU-only mode has no positional sensor. Keep a stable standing eye
        // height and report a valid orientation pose even before the first
        // phone packet arrives, so SteamVR can initialize its standing universe.
        pose.vecPosition[0] = 0.0;
        pose.vecPosition[1] = 1.65;
        pose.vecPosition[2] = 0.0;
        pose.result = vr::TrackingResult_Running_OK;
        pose.poseIsValid = true;
        pose.deviceIsConnected = true;
        pose.willDriftInYaw = true;
        pose.shouldApplyHeadModel = false;
        return pose;
    }

private:
    static bool SendAll(SOCKET client, const char* data, int length) {
        int sent = 0;
        while (sent < length) {
            const int count = ::send(client, data + sent, length - sent, 0);
            if (count <= 0) return false;
            sent += count;
        }
        return true;
    }

    void FrameServerLoop() {
        while (!stopping_.load()) {
            SOCKET client = ::accept(frameListenSocket_, nullptr, nullptr);
            if (client == INVALID_SOCKET) {
                if (stopping_.load()) break;
                continue;
            }
            DWORD timeout = 1000;
            setsockopt(client, SOL_SOCKET, SO_SNDTIMEO,
                       reinterpret_cast<const char*>(&timeout), sizeof(timeout));
            uint64_t sentSequence = 0;
            while (!stopping_.load()) {
                std::vector<uint8_t> frame;
                uint64_t sequence = 0;
                {
                    std::unique_lock<std::mutex> lock(frameMutex_);
                    frameCondition_.wait_for(lock, std::chrono::milliseconds(500), [&] {
                        return stopping_.load() || frameSequence_ != sentSequence;
                    });
                    if (stopping_.load()) break;
                    if (frameSequence_ == sentSequence || latestFrame_.empty()) continue;
                    frame = latestFrame_;
                    sequence = frameSequence_;
                }
                if (frame.size() > 4'000'000) continue;
                const uint32_t length = htonl(static_cast<uint32_t>(frame.size()));
                if (!SendAll(client, reinterpret_cast<const char*>(&length), sizeof(length)) ||
                    !SendAll(client, reinterpret_cast<const char*>(frame.data()), static_cast<int>(frame.size())))
                    break;
                sentSequence = sequence;
            }
            closesocket(client);
        }
    }

public:
    void SubmitFrame(vr::SharedTextureHandle_t sharedHandle) {
        if (!d3dDevice_ || sharedHandle == INVALID_SHARED_TEXTURE_HANDLE) return;
        HRESULT comResult = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        const bool uninitializeCom = SUCCEEDED(comResult);
        Microsoft::WRL::ComPtr<ID3D11Texture2D> source;
        const HANDLE shared = reinterpret_cast<HANDLE>(static_cast<uintptr_t>(sharedHandle));
        if (FAILED(d3dDevice_->OpenSharedResource(shared, IID_PPV_ARGS(&source)))) {
            if (uninitializeCom) CoUninitialize();
            return;
        }
        D3D11_TEXTURE2D_DESC desc{};
        source->GetDesc(&desc);
        if (desc.Width == 0 || desc.Height == 0 || desc.SampleDesc.Count != 1 ||
            desc.Width > 8192 || desc.Height > 8192) {
            if (uninitializeCom) CoUninitialize();
            return;
        }
        D3D11_TEXTURE2D_DESC stagingDesc = desc;
        stagingDesc.Usage = D3D11_USAGE_STAGING;
        stagingDesc.BindFlags = 0;
        stagingDesc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        stagingDesc.MiscFlags = 0;
        Microsoft::WRL::ComPtr<ID3D11Texture2D> staging;
        if (FAILED(d3dDevice_->CreateTexture2D(&stagingDesc, nullptr, &staging))) {
            if (uninitializeCom) CoUninitialize();
            return;
        }
        d3dContext_->CopyResource(staging.Get(), source.Get());
        D3D11_MAPPED_SUBRESOURCE mapped{};
        if (FAILED(d3dContext_->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped))) {
            if (uninitializeCom) CoUninitialize();
            return;
        }
        std::vector<BYTE> pixels(static_cast<size_t>(desc.Width) * desc.Height * 4);
        for (uint32_t y = 0; y < desc.Height; ++y)
            std::memcpy(pixels.data() + static_cast<size_t>(y) * desc.Width * 4,
                        static_cast<const BYTE*>(mapped.pData) + static_cast<size_t>(y) * mapped.RowPitch,
                        static_cast<size_t>(desc.Width) * 4);
        d3dContext_->Unmap(staging.Get(), 0);

        Microsoft::WRL::ComPtr<IWICImagingFactory> factory;
        Microsoft::WRL::ComPtr<IWICBitmap> bitmap;
        Microsoft::WRL::ComPtr<IWICStream> wicStream;
        Microsoft::WRL::ComPtr<IWICBitmapEncoder> encoder;
        Microsoft::WRL::ComPtr<IWICBitmapFrameEncode> frame;
        Microsoft::WRL::ComPtr<IPropertyBag2> properties;
        Microsoft::WRL::ComPtr<IStream> memoryStream;
        std::vector<uint8_t> jpeg;
        HRESULT hr = CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                                      IID_PPV_ARGS(&factory));
        if (SUCCEEDED(hr)) hr = factory->CreateBitmapFromMemory(
            desc.Width, desc.Height, GUID_WICPixelFormat32bppBGRA, desc.Width * 4,
            static_cast<UINT>(pixels.size()), pixels.data(), &bitmap);
        if (SUCCEEDED(hr)) hr = CreateStreamOnHGlobal(nullptr, TRUE, &memoryStream);
        if (SUCCEEDED(hr)) hr = factory->CreateEncoder(GUID_ContainerFormatJpeg, nullptr, &encoder);
        if (SUCCEEDED(hr)) hr = encoder->Initialize(memoryStream.Get(), WICBitmapEncoderNoCache);
        if (SUCCEEDED(hr)) hr = encoder->CreateNewFrame(&frame, &properties);
        if (SUCCEEDED(hr)) {
            PROPBAG2 option{};
            option.pstrName = const_cast<LPOLESTR>(L"ImageQuality");
            VARIANT quality;
            VariantInit(&quality);
            quality.vt = VT_R4;
            quality.fltVal = 0.58f;
            properties->Write(1, &option, &quality);
            VariantClear(&quality);
            hr = frame->Initialize(properties.Get());
        }
        if (SUCCEEDED(hr)) hr = frame->SetSize(desc.Width, desc.Height);
        WICPixelFormatGUID format = GUID_WICPixelFormat24bppBGR;
        if (SUCCEEDED(hr)) hr = frame->SetPixelFormat(&format);
        if (SUCCEEDED(hr)) hr = frame->WriteSource(bitmap.Get(), nullptr);
        if (SUCCEEDED(hr)) hr = frame->Commit();
        if (SUCCEEDED(hr)) hr = encoder->Commit();
        if (SUCCEEDED(hr)) {
            STATSTG stat{};
            hr = memoryStream->Stat(&stat, STATFLAG_NONAME);
            if (SUCCEEDED(hr) && stat.cbSize.QuadPart > 0 && stat.cbSize.QuadPart <= 4'000'000) {
                LARGE_INTEGER zero{};
                memoryStream->Seek(zero, STREAM_SEEK_SET, nullptr);
                HGLOBAL global = nullptr;
                if (SUCCEEDED(GetHGlobalFromStream(memoryStream.Get(), &global))) {
                    const auto* data = static_cast<const uint8_t*>(GlobalLock(global));
                    if (data) {
                        jpeg.assign(data, data + static_cast<size_t>(stat.cbSize.QuadPart));
                        GlobalUnlock(global);
                    }
                }
            }
        }
        if (uninitializeCom) CoUninitialize();
        if (jpeg.empty()) return;
        {
            std::lock_guard<std::mutex> lock(frameMutex_);
            latestFrame_ = std::move(jpeg);
            ++frameSequence_;
        }
        frameCondition_.notify_one();
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

    void ReceiveHandInputLoop() {
        static const std::regex sidePattern(R"rx("handedness"\s*:\s*"(Left|Right)")rx");
        static const std::regex confidencePattern(R"rx("confidence"\s*:\s*([-+0-9.eE]+))rx");
        static const std::regex worldPattern(R"rx("worldLandmarks"\s*:\s*\[([\s\S]*?)\])rx");
        static const std::regex rootPattern(
            R"rx("rootPosition"\s*:\s*\{\s*"x"\s*:\s*([-+0-9.eE]+)\s*,\s*"y"\s*:\s*([-+0-9.eE]+)\s*,\s*"z"\s*:\s*([-+0-9.eE]+)\s*\})rx");
        static const std::regex pointPattern(
            R"rx(\{\s*"x"\s*:\s*([-+0-9.eE]+)\s*,\s*"y"\s*:\s*([-+0-9.eE]+)\s*,\s*"z"\s*:\s*([-+0-9.eE]+)\s*\})rx");
        char buffer[16384];
        while (!stopping_.load()) {
            const int count = recvfrom(handSocket_, buffer, sizeof(buffer) - 1, 0, nullptr, nullptr);
            if (count <= 0) continue;
            buffer[count] = '\0';
            const std::string packet(buffer, static_cast<size_t>(count));
            try {
                bool seenLeft = false, seenRight = false;
                for (std::sregex_iterator it(packet.begin(), packet.end(), sidePattern), last; it != last; ++it) {
                    const auto side = (*it)[1].str();
                    const auto begin = static_cast<size_t>((*it).position());
                    size_t finish = packet.size();
                    const auto remaining = packet.substr(begin + static_cast<size_t>((*it).length()));
                    std::smatch nextMatch;
                    if (std::regex_search(remaining, nextMatch, sidePattern))
                        finish = begin + static_cast<size_t>((*it).length()) + static_cast<size_t>(nextMatch.position());
                    const auto object = packet.substr(begin, finish - begin);
                    auto* controller = side == "Left" ? leftController_.get() : rightController_.get();
                    if (!controller) continue;

                    std::smatch confidenceMatch, worldMatch, rootMatch;
                    if (!std::regex_search(object, confidenceMatch, confidencePattern) ||
                        !std::regex_search(object, worldMatch, worldPattern) ||
                        !std::regex_search(object, rootMatch, rootPattern)) {
                        controller->ClearSkeleton();
                        if (side == "Left") seenLeft = true; else seenRight = true;
                        continue;
                    }
                    const float confidence = std::stof(confidenceMatch[1].str());
                    if (!std::isfinite(confidence) || confidence < 0.0f || confidence > 1.0f) {
                        controller->ClearSkeleton();
                        if (side == "Left") seenLeft = true; else seenRight = true;
                        continue;
                    }
                    std::array<std::array<float, 3>, 21> points{};
                    const std::string coordinates = worldMatch[1].str();
                    size_t pointCount = 0;
                    for (std::sregex_iterator pointIt(coordinates.begin(), coordinates.end(), pointPattern), pointEnd;
                         pointIt != pointEnd && pointCount < points.size(); ++pointIt) {
                        points[pointCount] = {
                            std::stof((*pointIt)[1].str()),
                            std::stof((*pointIt)[2].str()),
                            std::stof((*pointIt)[3].str())
                        };
                        ++pointCount;
                    }
                    if (pointCount == points.size()) {
                        const std::array<float, 3> rootPositionHead{
                            std::stof(rootMatch[1].str()),
                            std::stof(rootMatch[2].str()),
                            std::stof(rootMatch[3].str())
                        };
                        controller->SetHandPose(points, confidence, rootPositionHead);
                    } else controller->ClearSkeleton();
                    if (side == "Left") seenLeft = true; else seenRight = true;
                }
                if (!seenLeft && leftController_) leftController_->ClearSkeleton();
                if (!seenRight && rightController_) rightController_->ClearSkeleton();
            } catch (...) {
                if (leftController_) leftController_->ClearSkeleton();
                if (rightController_) rightController_->ClearSkeleton();
            }
        }
    }

    std::unique_ptr<VRoomHmd> hmd_;
    std::unique_ptr<VRoomController> leftController_;
    std::unique_ptr<VRoomController> rightController_;
    std::unique_ptr<VRoomDisplayRedirect> displayRedirect_;
    Microsoft::WRL::ComPtr<ID3D11Device> d3dDevice_;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext> d3dContext_;
    SOCKET socket_ = INVALID_SOCKET;
    SOCKET handSocket_ = INVALID_SOCKET;
    SOCKET frameListenSocket_ = INVALID_SOCKET;
    std::thread receiver_;
    std::thread handReceiver_;
    std::thread frameThread_;
    std::mutex frameMutex_;
    std::condition_variable frameCondition_;
    std::vector<uint8_t> latestFrame_;
    uint64_t frameSequence_ = 0;
    std::atomic<bool> stopping_{false};
    std::atomic<bool> hasPose_{false};
    std::atomic<double> qx_{0}, qy_{0}, qz_{0}, qw_{1};
    bool winsockReady_ = false;
};

vr::EVRInitError VRoomDisplayRedirect::Activate(uint32_t objectId) {
    objectId_ = objectId;
    auto* properties = vr::VRProperties();
    const auto container = properties->TrackedDeviceToPropertyContainer(objectId);
    properties->SetStringProperty(container, vr::Prop_TrackingSystemName_String, "VRoom");
    properties->SetStringProperty(container, vr::Prop_ModelNumber_String, "VRoom Wireless Display");
    properties->SetStringProperty(container, vr::Prop_SerialNumber_String, "VRoom_Display_001");
    properties->SetStringProperty(container, vr::Prop_ManufacturerName_String, "VRoom");
    vr::VRDriverLog()->Log("VRoom: display redirect activated");
    return vr::VRInitError_None;
}

vr::DriverPose_t VRoomDisplayRedirect::GetPose() {
    vr::DriverPose_t pose{};
    pose.qWorldFromDriverRotation = {1.0, 0.0, 0.0, 0.0};
    pose.qDriverFromHeadRotation = {1.0, 0.0, 0.0, 0.0};
    pose.qRotation = {1.0, 0.0, 0.0, 0.0};
    pose.result = vr::TrackingResult_Uninitialized;
    pose.poseIsValid = false;
    pose.deviceIsConnected = true;
    return pose;
}

void VRoomDisplayRedirect::Present(const vr::PresentInfo_t* info, uint32_t size) {
    if (!info || size < sizeof(vr::PresentInfo_t)) return;
    // Per OpenVR's contract, Present must queue work and return quickly.
    // The potentially blocking GPU readback/JPEG encode happens in WaitForPresent.
    std::lock_guard<std::mutex> lock(presentMutex_);
    pendingHandle_ = info->backbufferTextureHandle;
    hasPendingFrame_ = pendingHandle_ != INVALID_SHARED_TEXTURE_HANDLE;
}

void VRoomDisplayRedirect::WaitForPresent() {
    vr::SharedTextureHandle_t handle = INVALID_SHARED_TEXTURE_HANDLE;
    {
        std::lock_guard<std::mutex> lock(presentMutex_);
        if (hasPendingFrame_) handle = pendingHandle_;
        hasPendingFrame_ = false;
    }
    if (handle != INVALID_SHARED_TEXTURE_HANDLE)
        provider_->SubmitFrame(handle);

    std::lock_guard<std::mutex> lock(presentMutex_);
    lastVsync_ = std::chrono::steady_clock::now();
    ++vsyncCounter_;
}

bool VRoomDisplayRedirect::GetTimeSinceLastVsync(float* seconds, uint64_t* frameCounter) {
    std::lock_guard<std::mutex> lock(presentMutex_);
    if (seconds) {
        *seconds = std::chrono::duration<float>(
            std::chrono::steady_clock::now() - lastVsync_).count();
    }
    if (frameCounter) *frameCounter = vsyncCounter_;
    return true;
}

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

    // Publish the standing/seated coordinate systems directly from the HMD.
    // SteamVR requires a driver-owned Chaperone setup for custom tracking drivers.
    const std::string chaperoneJson = BuildChaperoneJson();
    properties->SetStringProperty(
        container, vr::Prop_DriverProvidedChaperoneJson_String, chaperoneJson.c_str());
    properties->SetUint64Property(
        container, vr::Prop_CurrentUniverseId_Uint64, kVRoomUniverseId);
    // The recenter-support property was removed from current OpenVR headers;
    // leave SteamVR's default recenter behavior enabled.
    vr::VRDriverLog()->Log("VRoom: standing universe and chaperone data published");
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
