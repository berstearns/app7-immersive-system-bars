# ═══════════════════════════════════════════════════════════════════════
#  domain-core/CMakeLists.txt — C projection of App7Domain.hs.
#
#  Consumption patterns:
#    (a) Standalone test     — cmake -S domain-core -B build/host
#                              cmake --build build/host
#                              ./build/host/round_trip
#                              (the executable is only built when this
#                               CMakeLists is the top-level project.)
#    (b) Android NDK (Ph 3)  — add_subdirectory(${ROOT}/domain-core ...)
#                              from shared/src/androidMain/cpp/CMakeLists.txt
#                              and link app7_domain_core into libapp7_domain.so
#    (c) Desktop JVM (Ph 4)  — same as (b) but the host-Linux toolchain
#                              is used and the artifact is packaged into
#                              shared/desktopMain/resources/META-INF/native/.
# ═══════════════════════════════════════════════════════════════════════
cmake_minimum_required(VERSION 3.22.1)
project(app7_domain C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

# OBJECT library: the primitive that Android/Desktop wrap into SHARED.
# We do NOT define the shared library here — that lives in the consumer's
# CMakeLists so per-target linker flags (e.g. -fuse-ld=lld for Android)
# don't leak into the standalone test build.
add_library(app7_domain_core OBJECT
    src/crop.c
    src/region.c
    src/permutation.c
    src/strerror.c
    src/annotation_pool.c
    src/session_tracker.c
    src/navigator.c
)
target_include_directories(app7_domain_core PUBLIC include)
target_compile_options(app7_domain_core PRIVATE
    -Wall -Wextra -Werror -Os -fvisibility=hidden
)

# STATIC convenience library for the standalone round_trip executable.
# (Object libraries can't be linked directly into executables on all
# CMake versions, so we wrap with STATIC here.)
add_library(app7_domain_static STATIC $<TARGET_OBJECTS:app7_domain_core>)
target_include_directories(app7_domain_static PUBLIC include)

# Standalone round_trip executable — only built when domain-core/ is the
# top-level project (consumers' add_subdirectory() builds the lib but not
# the test).
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    add_executable(round_trip test/round_trip.c)
    target_link_libraries(round_trip PRIVATE app7_domain_static)
    # Math library needed for fabsf on some toolchains; harmless if not.
    target_link_libraries(round_trip PRIVATE m)
endif()
