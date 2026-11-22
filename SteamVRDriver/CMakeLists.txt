cmake_minimum_required(VERSION 3.20)
project(driver_vroom LANGUAGES CXX)

if(NOT DEFINED OPENVR_INCLUDE_DIR)
  message(FATAL_ERROR "Set OPENVR_INCLUDE_DIR to the OpenVR headers directory")
endif()

add_library(driver_vroom SHARED src/driver.cpp)
target_include_directories(driver_vroom PRIVATE "${OPENVR_INCLUDE_DIR}")
target_compile_features(driver_vroom PRIVATE cxx_std_17)
target_compile_definitions(driver_vroom PRIVATE VR_API_EXPORT)
target_link_libraries(driver_vroom PRIVATE ws2_32 d3d11 dxgi windowscodecs ole32 oleaut32)
set_target_properties(driver_vroom PROPERTIES PREFIX "" OUTPUT_NAME "driver_vroom")
