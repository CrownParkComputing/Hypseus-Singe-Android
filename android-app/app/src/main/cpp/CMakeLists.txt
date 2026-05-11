cmake_minimum_required(VERSION 3.22.1)

project(hypseus_android_app)

# All dependencies (SDL2, Vorbis, libzip, libmpeg2) are built from source
# by src/CMakeLists.txt via BuildAndroidDeps.cmake and BuildLibMPEG2.cmake.
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../../src hypseus_build)

