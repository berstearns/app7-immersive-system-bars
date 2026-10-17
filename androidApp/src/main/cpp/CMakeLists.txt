# ═══════════════════════════════════════════════════════════════════════
#  androidApp/src/main/cpp/CMakeLists.txt — AGP-driven NDK build.
#
#  The domain is Rust now (domain-rust/ is the algebraic source of truth;
#  domain-core keeps the frozen header, the JNI shim, and the round_trip
#  oracle).  AGP runs this once per ABI in `defaultConfig.ndk.abiFilters`;
#  we link the JNI shim against the PREBUILT Rust staticlib for that ABI:
#      domain-rust/target/<rust-triple>/release/libapp7_domain.a
#  built by domain-rust/build-android.sh (cargo + NDK clang linker).
#
#  Path math (from this file to the dev folder root):
#    androidApp/src/main/cpp/ → ../../../../   (4 ..)
# ═══════════════════════════════════════════════════════════════════════
cmake_minimum_required(VERSION 3.22.1)
project(app7_domain_android C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(DEV_ROOT     ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)
set(DOMAIN_CORE  ${DEV_ROOT}/domain-core)
set(DOMAIN_RUST  ${DEV_ROOT}/domain-rust)

# ANDROID_ABI → Rust target triple
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TRIPLE aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RUST_TRIPLE armv7-linux-androideabi)
elseif(ANDROID_ABI STREQUAL "x86")
    set(RUST_TRIPLE i686-linux-android)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TRIPLE x86_64-linux-android)
else()
    message(FATAL_ERROR "app7: no Rust triple mapping for ANDROID_ABI=${ANDROID_ABI}")
endif()

set(RUST_ARCHIVE ${DOMAIN_RUST}/target/${RUST_TRIPLE}/release/libapp7_domain.a)
if(NOT EXISTS ${RUST_ARCHIVE})
    message(FATAL_ERROR
        "app7: missing Rust domain staticlib for ${ANDROID_ABI}:\n"
        "  ${RUST_ARCHIVE}\n"
        "Build it first:  domain-rust/build-android.sh ${ANDROID_ABI}\n"
        "(requires rustup Android targets — the script prints setup steps).")
endif()

add_library(app7_domain SHARED
    ${DOMAIN_CORE}/jni/app7_domain_jni.c
)
target_include_directories(app7_domain PRIVATE ${DOMAIN_CORE}/include)
target_compile_options(app7_domain PRIVATE -Wall -Wextra -Werror -Os)
target_link_libraries(app7_domain PRIVATE ${RUST_ARCHIVE})

# Android's `log` lib — kept for future debugging parity with the C build.
find_library(log-lib log)
if(log-lib)
    target_link_libraries(app7_domain PRIVATE ${log-lib})
endif()
