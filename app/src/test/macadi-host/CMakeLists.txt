# Host build of the emulated Mac's Anisette core, for checking it against a reference without a
# device. See README.md.

cmake_minimum_required(VERSION 3.22.1)
project(macadi_host CXX C)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
include(${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp/macadi/MacAdi.cmake)

add_executable(macadi_cli macadi_cli.cpp)
target_link_libraries(macadi_cli PRIVATE macadi_core)
