cmake_minimum_required(VERSION 3.24)

project(nuvio_player_bridge_linux LANGUAGES C CXX)

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")
    message(FATAL_ERROR "This player bridge is Linux-only")
endif()

find_package(JNI REQUIRED COMPONENTS JVM AWT)
find_package(Threads REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(GTK3 REQUIRED IMPORTED_TARGET gtk+-3.0)
pkg_check_modules(WEBKITGTK REQUIRED IMPORTED_TARGET webkit2gtk-4.1)
# The controls overlay uses Xlib; JAWT also needs the protocol headers.
find_path(X11_INCLUDE_DIR NAMES X11/Xlib.h REQUIRED)
find_path(X11_PROTOCOL_INCLUDE_DIR NAMES X11/X.h REQUIRED)
find_library(X11_LIBRARY NAMES X11 REQUIRED)

set(MPV_ROOT "$ENV{MPV_ROOT}" CACHE PATH "Root containing include/mpv and lib/libmpv.so")
if(MPV_ROOT)
    set(MPV_INCLUDE_DIR "${MPV_ROOT}/include")
    set(MPV_LIBRARY "${MPV_ROOT}/lib/libmpv.so")
else()
    # Avoid pkg-config: some installations have incomplete transitive .pc metadata.
    find_path(MPV_INCLUDE_DIR NAMES mpv/client.h REQUIRED)
    find_library(MPV_LIBRARY NAMES mpv REQUIRED)
endif()

if(NOT EXISTS "${MPV_INCLUDE_DIR}/mpv/client.h" OR
   NOT EXISTS "${MPV_INCLUDE_DIR}/mpv/render.h" OR
   NOT EXISTS "${MPV_LIBRARY}")
    message(FATAL_ERROR "libmpv headers/library not found; set MPV_ROOT to the mpv installation prefix")
endif()

add_library(player_bridge SHARED player_bridge.cpp awt_view_resolver.cpp controls_overlay.cpp seek_thumbnails.cpp playback_inhibitor.cpp screensaver_idle.cpp mpris_session.cpp linux_gamepad.cpp window_chrome.cpp window_chrome_jni.cpp pip_geometry_jni.cpp hdr_wayland_session.cpp flatpak_update.cpp flatpak_update_jni.cpp)
# Optional X11 idle fallback. Wayland sessions never use an XWayland global idle estimate.
find_path(XSS_INCLUDE_DIR NAMES X11/extensions/scrnsaver.h)
if(XSS_INCLUDE_DIR)
    target_compile_definitions(player_bridge PRIVATE NUVIO_HAVE_XSS)
    target_include_directories(player_bridge SYSTEM PRIVATE "${XSS_INCLUDE_DIR}")
    target_link_libraries(player_bridge PRIVATE ${CMAKE_DL_LIBS})
endif()
target_compile_features(player_bridge PRIVATE cxx_std_17)
target_include_directories(player_bridge PRIVATE "${MPV_INCLUDE_DIR}")
target_include_directories(player_bridge SYSTEM PRIVATE
    ${JNI_INCLUDE_DIRS} "${X11_INCLUDE_DIR}" "${X11_PROTOCOL_INCLUDE_DIR}")
target_link_libraries(player_bridge PRIVATE JNI::JNI JNI::AWT Threads::Threads "${MPV_LIBRARY}"
    PkgConfig::GTK3 PkgConfig::WEBKITGTK "${X11_LIBRARY}")
target_link_options(player_bridge PRIVATE "LINKER:-z,defs")

get_filename_component(MPV_LIBRARY_DIR "${MPV_LIBRARY}" DIRECTORY)
option(NUVIO_PACKAGE_BUILD "Use relocatable jpackage Linux library layout" OFF)
set_target_properties(player_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED YES
    CXX_EXTENSIONS OFF
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
    # Local development only: resolve libmpv without requiring LD_LIBRARY_PATH.
    BUILD_RPATH "$ORIGIN;${MPV_LIBRARY_DIR}"
)
if(NUVIO_PACKAGE_BUILD)
    set_target_properties(player_bridge PROPERTIES
        BUILD_WITH_INSTALL_RPATH YES
        INSTALL_RPATH "$ORIGIN;$ORIGIN/../runtime/lib")
endif()

# quickjs-kt's JNI library from the vendored, patched sources (../quickjs-kt/README.md): each
# JS -> Kotlin binding call gets its own JNI local frame, so a plugin job no longer keeps every
# argument it passed alive until the job ends. Same sources and defines as upstream's Windows
# build (buildWindowsQuickJs); the app points quickjs-kt at it (LinuxQuickJsNativeLibrary.kt).
set(QUICKJS_KT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../quickjs-kt")
file(READ "${QUICKJS_KT_DIR}/quickjs/VERSION" QUICKJS_VERSION)
string(STRIP "${QUICKJS_VERSION}" QUICKJS_VERSION)
file(GLOB QUICKJS_KT_SOURCES CONFIGURE_DEPENDS
    "${QUICKJS_KT_DIR}/common/*.c" "${QUICKJS_KT_DIR}/jni/*.c" "${QUICKJS_KT_DIR}/jni/mapping/*.c")
list(SORT QUICKJS_KT_SOURCES)
add_library(quickjs SHARED
    "${QUICKJS_KT_DIR}/quickjs/cutils.c" "${QUICKJS_KT_DIR}/quickjs/dtoa.c"
    "${QUICKJS_KT_DIR}/quickjs/libregexp.c" "${QUICKJS_KT_DIR}/quickjs/libunicode.c"
    "${QUICKJS_KT_DIR}/quickjs/quickjs.c" ${QUICKJS_KT_SOURCES})
target_compile_definitions(quickjs PRIVATE _GNU_SOURCE CONFIG_BIGNUM "CONFIG_VERSION=\"${QUICKJS_VERSION}\"")
target_include_directories(quickjs PRIVATE "${QUICKJS_KT_DIR}/quickjs" "${QUICKJS_KT_DIR}/common"
    "${QUICKJS_KT_DIR}/c-vector" "${QUICKJS_KT_DIR}/jni" "${QUICKJS_KT_DIR}/jni/mapping")
target_include_directories(quickjs SYSTEM PRIVATE ${JNI_INCLUDE_DIRS})
target_compile_options(quickjs PRIVATE -O2 -w)
target_link_libraries(quickjs PRIVATE m Threads::Threads ${CMAKE_DL_LIBS})
target_link_options(quickjs PRIVATE "LINKER:-z,defs")
set_target_properties(quickjs PROPERTIES C_STANDARD 99 C_EXTENSIONS ON)

# Built explicitly by the opt-in smoke suite; no test hooks in player_bridge.
add_executable(linux_seek_thumbnail_probe EXCLUDE_FROM_ALL
    tests/seek_thumbnail_probe.cpp seek_thumbnails.cpp)
target_compile_features(linux_seek_thumbnail_probe PRIVATE cxx_std_17)
target_compile_definitions(linux_seek_thumbnail_probe PRIVATE NUVIO_SEEK_THUMBNAIL_TESTING)
target_include_directories(linux_seek_thumbnail_probe PRIVATE "${MPV_INCLUDE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(linux_seek_thumbnail_probe PRIVATE Threads::Threads "${MPV_LIBRARY}" PkgConfig::GTK3)
target_link_options(linux_seek_thumbnail_probe PRIVATE "LINKER:--wrap=mpv_initialize")
set_target_properties(linux_seek_thumbnail_probe PROPERTIES BUILD_RPATH "${MPV_LIBRARY_DIR}")

# Explicit isolated-display diagnostic, excluded from every ordinary build/test.
add_executable(linux_controls_paint_probe EXCLUDE_FROM_ALL
    tests/controls_paint_probe.cpp controls_overlay.cpp window_chrome.cpp)
target_compile_features(linux_controls_paint_probe PRIVATE cxx_std_17)
target_include_directories(linux_controls_paint_probe PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${X11_INCLUDE_DIR}")
target_link_libraries(linux_controls_paint_probe PRIVATE Threads::Threads PkgConfig::GTK3
    PkgConfig::WEBKITGTK "${X11_LIBRARY}")

add_executable(linux_script_probe EXCLUDE_FROM_ALL
    tests/script_probe.cpp controls_overlay.cpp window_chrome.cpp)
target_compile_features(linux_script_probe PRIVATE cxx_std_17)
target_include_directories(linux_script_probe PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${X11_INCLUDE_DIR}")
target_link_libraries(linux_script_probe PRIVATE Threads::Threads PkgConfig::GTK3
    PkgConfig::WEBKITGTK "${X11_LIBRARY}")

add_executable(linux_cursor_hide_probe EXCLUDE_FROM_ALL
    tests/cursor_hide_probe.cpp controls_overlay.cpp window_chrome.cpp)
target_compile_features(linux_cursor_hide_probe PRIVATE cxx_std_17)
target_include_directories(linux_cursor_hide_probe PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${X11_INCLUDE_DIR}")
target_link_libraries(linux_cursor_hide_probe PRIVATE Threads::Threads PkgConfig::GTK3
    PkgConfig::WEBKITGTK "${X11_LIBRARY}" Xfixes)

add_executable(linux_hud_wm_close_probe EXCLUDE_FROM_ALL
    tests/hud_wm_close_probe.cpp controls_overlay.cpp window_chrome.cpp)
target_compile_features(linux_hud_wm_close_probe PRIVATE cxx_std_17)
target_include_directories(linux_hud_wm_close_probe PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${X11_INCLUDE_DIR}")
target_link_libraries(linux_hud_wm_close_probe PRIVATE Threads::Threads PkgConfig::GTK3
    PkgConfig::WEBKITGTK "${X11_LIBRARY}")

# Live TV cache-only seeks (live_cache_seek.h): pure range/offset rules, no libmpv calls.
add_executable(linux_live_cache_seek_test EXCLUDE_FROM_ALL tests/live_cache_seek_test.cpp)
target_compile_features(linux_live_cache_seek_test PRIVATE cxx_std_17)
target_compile_options(linux_live_cache_seek_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_live_cache_seek_test PRIVATE "${MPV_INCLUDE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")

# HUD label map kept aside from coalesced controls payloads (hud_labels_carry.h): pure strings.
add_executable(linux_hud_labels_carry_test EXCLUDE_FROM_ALL tests/hud_labels_carry_test.cpp)
target_compile_features(linux_hud_labels_carry_test PRIVATE cxx_std_17)
target_compile_options(linux_hud_labels_carry_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_hud_labels_carry_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

# Auto crop (auto_crop.h): its rules against a scripted host, then real libmpv with null outputs.
add_executable(linux_auto_crop_test EXCLUDE_FROM_ALL tests/auto_crop_test.cpp)
target_compile_features(linux_auto_crop_test PRIVATE cxx_std_17)
target_compile_options(linux_auto_crop_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_auto_crop_test PRIVATE "${MPV_INCLUDE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(linux_auto_crop_test PRIVATE "${MPV_LIBRARY}")
set_target_properties(linux_auto_crop_test PROPERTIES BUILD_RPATH "${MPV_LIBRARY_DIR}")

# Subtitle sync's audio decodes (speech_spot.h) with real libmpv: ao=pcm to a file, no device.
add_executable(linux_speech_spot_test EXCLUDE_FROM_ALL tests/speech_spot_test.cpp)
target_compile_features(linux_speech_spot_test PRIVATE cxx_std_17)
target_compile_options(linux_speech_spot_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_speech_spot_test PRIVATE "${MPV_INCLUDE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(linux_speech_spot_test PRIVATE "${MPV_LIBRARY}")
set_target_properties(linux_speech_spot_test PROPERTIES BUILD_RPATH "${MPV_LIBRARY_DIR}")

# Addon subtitle adds off the caller's thread (subtitle_add.h): real libmpv, loopback HTTP only.
add_executable(linux_subtitle_add_test EXCLUDE_FROM_ALL tests/subtitle_add_test.cpp)
target_compile_features(linux_subtitle_add_test PRIVATE cxx_std_17)
target_compile_options(linux_subtitle_add_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_subtitle_add_test PRIVATE "${MPV_INCLUDE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(linux_subtitle_add_test PRIVATE "${MPV_LIBRARY}" Threads::Threads)
set_target_properties(linux_subtitle_add_test PROPERTIES BUILD_RPATH "${MPV_LIBRARY_DIR}")

# Real-libmpv separate-audio regression probe: null outputs, no display/GTK/JNI.
add_executable(linux_separate_audio_test EXCLUDE_FROM_ALL tests/separate_audio_test.cpp)
target_compile_features(linux_separate_audio_test PRIVATE cxx_std_17)
target_compile_options(linux_separate_audio_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_separate_audio_test PRIVATE "${MPV_INCLUDE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(linux_separate_audio_test PRIVATE "${MPV_LIBRARY}")
set_target_properties(linux_separate_audio_test PROPERTIES BUILD_RPATH "${MPV_LIBRARY_DIR}")

# The player's session logs (session_log.h): files in a temp folder; no libmpv, display or JNI.
add_executable(linux_session_log_test EXCLUDE_FROM_ALL tests/session_log_test.cpp)
target_compile_features(linux_session_log_test PRIVATE cxx_std_17)
target_compile_options(linux_session_log_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_session_log_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

# HTTP/seek event translation and null-output libmpv probe; no display/GTK/JNI.
add_executable(linux_http_recovery_test EXCLUDE_FROM_ALL tests/http_recovery_test.cpp)
target_compile_features(linux_http_recovery_test PRIVATE cxx_std_17)
target_compile_options(linux_http_recovery_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_http_recovery_test PRIVATE "${MPV_INCLUDE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(linux_http_recovery_test PRIVATE "${MPV_LIBRARY}")
set_target_properties(linux_http_recovery_test PROPERTIES BUILD_RPATH "${MPV_LIBRARY_DIR}")

# SPDIF recovery policy and a test-only real-libmpv shim; no display/GTK/JNI.
add_executable(linux_audio_recovery_test EXCLUDE_FROM_ALL tests/audio_recovery_test.cpp)
target_compile_features(linux_audio_recovery_test PRIVATE cxx_std_17)
target_compile_options(linux_audio_recovery_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_audio_recovery_test PRIVATE "${MPV_INCLUDE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(linux_audio_recovery_test PRIVATE "${MPV_LIBRARY}")
set_target_properties(linux_audio_recovery_test PROPERTIES BUILD_RPATH "${MPV_LIBRARY_DIR}")

add_library(linux_audio_recovery_probe SHARED EXCLUDE_FROM_ALL tests/audio_recovery_probe.cpp)
target_compile_features(linux_audio_recovery_probe PRIVATE cxx_std_17)
target_compile_options(linux_audio_recovery_probe PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_audio_recovery_probe PRIVATE "${MPV_INCLUDE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(linux_audio_recovery_probe PRIVATE "${MPV_LIBRARY}")
set_target_properties(linux_audio_recovery_probe PROPERTIES BUILD_RPATH "${MPV_LIBRARY_DIR}")

# Deterministic chrome policy fixtures; no display, WM, mpv or JNI initialization.
add_executable(linux_pip_geometry_dispatch_test EXCLUDE_FROM_ALL tests/pip_geometry_dispatch_test.cpp)
target_compile_features(linux_pip_geometry_dispatch_test PRIVATE cxx_std_17)
target_compile_options(linux_pip_geometry_dispatch_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_pip_geometry_dispatch_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(linux_pip_geometry_dispatch_test PRIVATE Threads::Threads)

add_executable(linux_pip_hud_geometry_test EXCLUDE_FROM_ALL tests/pip_hud_geometry_test.cpp)
target_compile_features(linux_pip_hud_geometry_test PRIVATE cxx_std_17)
target_compile_options(linux_pip_hud_geometry_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_pip_hud_geometry_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

add_executable(linux_window_chrome_test EXCLUDE_FROM_ALL tests/window_chrome_test.cpp window_chrome.cpp)
target_compile_features(linux_window_chrome_test PRIVATE cxx_std_17)
target_compile_options(linux_window_chrome_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_window_chrome_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

# Deterministic controller fixtures; no real devices, display, mpv or JNI initialization.
add_executable(linux_gamepad_test EXCLUDE_FROM_ALL tests/linux_gamepad_test.cpp linux_gamepad.cpp)
target_compile_features(linux_gamepad_test PRIVATE cxx_std_17)
target_compile_definitions(linux_gamepad_test PRIVATE NUVIO_GAMEPAD_TEST)
target_compile_options(linux_gamepad_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_gamepad_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

# Read-only idle backend lifecycle probe; does not initialize mpv or GTK.
add_executable(linux_screensaver_idle_probe EXCLUDE_FROM_ALL
    tests/screensaver_idle_probe.cpp screensaver_idle.cpp)
target_compile_features(linux_screensaver_idle_probe PRIVATE cxx_std_17)
target_include_directories(linux_screensaver_idle_probe SYSTEM PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(linux_screensaver_idle_probe PRIVATE PkgConfig::GTK3)
if(XSS_INCLUDE_DIR)
    target_compile_definitions(linux_screensaver_idle_probe PRIVATE NUVIO_HAVE_XSS)
    target_include_directories(linux_screensaver_idle_probe SYSTEM PRIVATE "${XSS_INCLUDE_DIR}" "${X11_INCLUDE_DIR}" "${X11_PROTOCOL_INCLUDE_DIR}")
    target_link_libraries(linux_screensaver_idle_probe PRIVATE ${CMAKE_DL_LIBS} "${X11_LIBRARY}")
endif()

# Private-bus Flatpak portal update contract (tests/flatpak_update_test.py).
add_executable(linux_flatpak_update_probe EXCLUDE_FROM_ALL tests/flatpak_update_probe.cpp flatpak_update.cpp)
target_compile_features(linux_flatpak_update_probe PRIVATE cxx_std_17)
target_compile_options(linux_flatpak_update_probe PRIVATE -Wall -Wextra -Werror)
target_link_libraries(linux_flatpak_update_probe PRIVATE PkgConfig::GTK3)

add_executable(linux_pip_hud_visibility_test EXCLUDE_FROM_ALL tests/pip_hud_visibility_test.cpp)
target_compile_features(linux_pip_hud_visibility_test PRIVATE cxx_std_17)
target_compile_options(linux_pip_hud_visibility_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_pip_hud_visibility_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

# Experimental Wayland HDR session: option contracts, key routing, capability detection
# and the bundled OSC script against real libmpv with null outputs; no display/GTK/JNI.
add_executable(linux_hdr_session_test EXCLUDE_FROM_ALL tests/hdr_session_test.cpp hdr_wayland_session.cpp)
target_compile_features(linux_hdr_session_test PRIVATE cxx_std_17)
target_compile_options(linux_hdr_session_test PRIVATE -Wall -Wextra -Werror)
target_compile_definitions(linux_hdr_session_test PRIVATE
    NUVIO_HDR_OSC_SCRIPT="${CMAKE_CURRENT_SOURCE_DIR}/../../resources/player-scripts/linux-hdr-osc.lua")
target_include_directories(linux_hdr_session_test PRIVATE "${MPV_INCLUDE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(linux_hdr_session_test PRIVATE "${MPV_LIBRARY}")
set_target_properties(linux_hdr_session_test PROPERTIES BUILD_RPATH "${MPV_LIBRARY_DIR}")

# Optional read-only HDR capability helper (nuvio-hdr-probe). Built only when the
# Wayland client library, wayland-scanner, wayland-protocols >= 1.41 (colour
# management) and Vulkan headers/loader are available. Without it the application
# fails closed: the experimental HDR session is never selected. Package builds
# (NUVIO_PACKAGE_BUILD) require it, so a package never silently omits the helper.
option(NUVIO_HDR_PROBE "Build the nuvio-hdr-probe capability helper when its dependencies exist" ON)
if(NUVIO_PACKAGE_BUILD AND NOT NUVIO_HDR_PROBE)
    message(FATAL_ERROR "NUVIO_PACKAGE_BUILD requires NUVIO_HDR_PROBE=ON")
endif()
set(NUVIO_WAYLAND_PROTOCOLS_DIR "" CACHE PATH "wayland-protocols pkgdatadir (>= 1.41)")
set(NUVIO_VULKAN_INCLUDE_DIR "" CACHE PATH "Directory containing vulkan/vulkan.h")
if(NUVIO_HDR_PROBE)
    pkg_check_modules(NUVIO_WAYLAND_CLIENT IMPORTED_TARGET wayland-client)
    find_program(NUVIO_WAYLAND_SCANNER NAMES wayland-scanner)
    if(NOT NUVIO_WAYLAND_PROTOCOLS_DIR)
        pkg_check_modules(NUVIO_WAYLAND_PROTOCOLS QUIET wayland-protocols>=1.41)
        if(NUVIO_WAYLAND_PROTOCOLS_FOUND)
            pkg_get_variable(NUVIO_WAYLAND_PROTOCOLS_DIR wayland-protocols pkgdatadir)
        endif()
    endif()
    set(NUVIO_COLOR_MANAGEMENT_XML
        "${NUVIO_WAYLAND_PROTOCOLS_DIR}/staging/color-management/color-management-v1.xml")
    find_path(NUVIO_VULKAN_HEADERS NAMES vulkan/vulkan.h HINTS "${NUVIO_VULKAN_INCLUDE_DIR}" NO_CACHE)
    find_library(NUVIO_VULKAN_LIBRARY NAMES vulkan HINTS "${MPV_LIBRARY_DIR}")
    if(NUVIO_WAYLAND_CLIENT_FOUND AND NUVIO_WAYLAND_SCANNER AND NUVIO_WAYLAND_PROTOCOLS_DIR AND
       EXISTS "${NUVIO_COLOR_MANAGEMENT_XML}" AND NUVIO_VULKAN_HEADERS AND NUVIO_VULKAN_LIBRARY)
        set(NUVIO_HDR_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/hdr-protocol")
        file(MAKE_DIRECTORY "${NUVIO_HDR_GENERATED}")
        add_custom_command(
            OUTPUT "${NUVIO_HDR_GENERATED}/color-management-v1-client-protocol.h"
                   "${NUVIO_HDR_GENERATED}/color-management-v1-protocol.c"
            COMMAND "${NUVIO_WAYLAND_SCANNER}" client-header "${NUVIO_COLOR_MANAGEMENT_XML}"
                    "${NUVIO_HDR_GENERATED}/color-management-v1-client-protocol.h"
            COMMAND "${NUVIO_WAYLAND_SCANNER}" private-code "${NUVIO_COLOR_MANAGEMENT_XML}"
                    "${NUVIO_HDR_GENERATED}/color-management-v1-protocol.c"
            DEPENDS "${NUVIO_COLOR_MANAGEMENT_XML}"
            VERBATIM)
        enable_language(C)
        add_executable(nuvio-hdr-probe nuvio_hdr_probe.cpp hdr_capability_probe.cpp
            "${NUVIO_HDR_GENERATED}/color-management-v1-protocol.c")
        target_compile_features(nuvio-hdr-probe PRIVATE cxx_std_17)
        target_compile_options(nuvio-hdr-probe PRIVATE $<$<COMPILE_LANGUAGE:CXX>:-Wall -Wextra -Werror>)
        target_include_directories(nuvio-hdr-probe PRIVATE "${NUVIO_HDR_GENERATED}")
        target_include_directories(nuvio-hdr-probe SYSTEM PRIVATE "${NUVIO_VULKAN_HEADERS}")
        target_link_libraries(nuvio-hdr-probe PRIVATE PkgConfig::NUVIO_WAYLAND_CLIENT "${NUVIO_VULKAN_LIBRARY}")
        target_link_options(nuvio-hdr-probe PRIVATE "LINKER:-z,defs")
        set_target_properties(nuvio-hdr-probe PROPERTIES BUILD_RPATH "$ORIGIN;${MPV_LIBRARY_DIR}")
        if(NUVIO_PACKAGE_BUILD)
            set_target_properties(nuvio-hdr-probe PROPERTIES
                BUILD_WITH_INSTALL_RPATH YES
                INSTALL_RPATH "$ORIGIN;$ORIGIN/../runtime/lib")
        endif()
        message(STATUS "nuvio-hdr-probe: enabled (${NUVIO_COLOR_MANAGEMENT_XML})")
    elseif(NUVIO_PACKAGE_BUILD)
        message(FATAL_ERROR "nuvio-hdr-probe is required for package builds: needs wayland-client, "
            "wayland-scanner, wayland-protocols >= 1.41 (NUVIO_WAYLAND_PROTOCOLS_DIR) and Vulkan "
            "headers (NUVIO_VULKAN_INCLUDE_DIR) plus the loader next to libmpv")
    else()
        message(STATUS "nuvio-hdr-probe: disabled (needs wayland-client, wayland-scanner, "
            "wayland-protocols >= 1.41 and Vulkan headers/loader); HDR session fails closed")
    endif()
endif()
