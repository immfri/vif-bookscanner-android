#
#  UVCCamera - CMake-Build (ersetzt das alte ndk-build/Android.mk-System)
#
#  Migrationshinweis: NDK r18+ hat ndk-build fuer neue Projekte durch CMake
#  ersetzt und GCC/gnustl entfernt (nur noch Clang + libc++). Diese
#  CMakeLists.txt bildet exakt die vier .so-Module nach, die die alten
#  Android.mk-Dateien erzeugt haben:
#    libjpeg-turbo1500.so, libusb100.so, libuvc.so, libUVCCamera.so
#  (siehe System.loadLibrary(...)-Aufrufe in UVCCamera.java)
#
#  SIMD-Reaktivierung (2026-07-22): die ARM-NEON-Pfade von libjpeg-turbo
#  (simd/jsimd_arm64_neon.S, simd/jsimd_arm_neon.S) sind normale GNU-
#  Assembler-Dateien (.S, C-Praeprozessor + GAS-Syntax) -- die braucht
#  KEINEN separaten Assembler wie yasm/nasm, Clangs eingebauter Assembler
#  baut sie ueber CMakes Standard-ASM-Sprachunterstuetzung direkt (siehe
#  enable_language(ASM) unten). Nur die x86/x86_64-SIMD-Pfade brauchen
#  echtes NASM (.asm-Dateien) -- das ist NICHT eingerichtet, x86_64
#  bleibt bewusst auf jsimd_none.c (kein reales Zielgeraet, nur
#  Emulator). Referenz fuer die korrekten Pro-ABI-Quelldateien: das
#  Original-Android.mk dieses Moduls (jni/libjpeg-turbo-1.5.0/Android.mk,
#  Zeilen ~120-143).
#
cmake_minimum_required(VERSION 3.22.1)
project(uvccamera)

enable_language(ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 14)

set(JNI_DIR ${CMAKE_CURRENT_SOURCE_DIR})

# ---------------------------------------------------------------------------
# 1) libjpeg-turbo1500 (aus libjpeg-turbo-1.5.0/Android.mk)
# ---------------------------------------------------------------------------
set(JPEG_DIR ${JNI_DIR}/libjpeg-turbo-1.5.0)

set(JPEG_SRC
	${JPEG_DIR}/jcapimin.c
	${JPEG_DIR}/jcapistd.c
	${JPEG_DIR}/jccoefct.c
	${JPEG_DIR}/jccolor.c
	${JPEG_DIR}/jcdctmgr.c
	${JPEG_DIR}/jchuff.c
	${JPEG_DIR}/jcinit.c
	${JPEG_DIR}/jcmainct.c
	${JPEG_DIR}/jcmarker.c
	${JPEG_DIR}/jcmaster.c
	${JPEG_DIR}/jcomapi.c
	${JPEG_DIR}/jcparam.c
	${JPEG_DIR}/jcphuff.c
	${JPEG_DIR}/jcprepct.c
	${JPEG_DIR}/jcsample.c
	${JPEG_DIR}/jctrans.c
	${JPEG_DIR}/jdapimin.c
	${JPEG_DIR}/jdapistd.c
	${JPEG_DIR}/jdatadst.c
	${JPEG_DIR}/jdatasrc.c
	${JPEG_DIR}/jdcoefct.c
	${JPEG_DIR}/jdcolor.c
	${JPEG_DIR}/jddctmgr.c
	${JPEG_DIR}/jdhuff.c
	${JPEG_DIR}/jdinput.c
	${JPEG_DIR}/jdmainct.c
	${JPEG_DIR}/jdmarker.c
	${JPEG_DIR}/jdmaster.c
	${JPEG_DIR}/jdmerge.c
	${JPEG_DIR}/jdphuff.c
	${JPEG_DIR}/jdpostct.c
	${JPEG_DIR}/jdsample.c
	${JPEG_DIR}/jdtrans.c
	${JPEG_DIR}/jerror.c
	${JPEG_DIR}/jfdctflt.c
	${JPEG_DIR}/jfdctfst.c
	${JPEG_DIR}/jfdctint.c
	${JPEG_DIR}/jidctflt.c
	${JPEG_DIR}/jidctfst.c
	${JPEG_DIR}/jidctint.c
	${JPEG_DIR}/jidctred.c
	${JPEG_DIR}/jquant1.c
	${JPEG_DIR}/jquant2.c
	${JPEG_DIR}/jutils.c
	${JPEG_DIR}/jmemmgr.c
	${JPEG_DIR}/jmemnobs.c
	${JPEG_DIR}/jaricom.c
	${JPEG_DIR}/jcarith.c
	${JPEG_DIR}/jdarith.c
	${JPEG_DIR}/turbojpeg.c
	${JPEG_DIR}/transupp.c
	${JPEG_DIR}/jdatadst-tj.c
	${JPEG_DIR}/jdatasrc-tj.c
)

# SIMD-Reaktivierung (2026-07-22): pro ABI die vom Original-Android.mk vorgegebenen
# NEON-Quellen statt des portablen jsimd_none.c-Fallbacks -- ANDROID_ABI wird vom
# NDK-Toolchain-File (android.toolchain.cmake) automatisch gesetzt, sobald AGPs
# externalNativeBuild diese CMakeLists.txt pro ABI-Ziel konfiguriert (siehe
# libuvccamera/build.gradle abiFilters). x86_64 hat KEINEN aktivierten SIMD-Pfad
# (braucht echtes NASM fuer die .asm-Dateien, siehe Kommentar oben) und bleibt auf
# jsimd_none.c -- korrekt und funktional identisch zum bisherigen Stand, nur ohne
# Beschleunigung (kein reales Zielgeraet, nur x86_64-Emulator).
if(ANDROID_ABI STREQUAL "arm64-v8a")
	list(APPEND JPEG_SRC ${JPEG_DIR}/simd/jsimd_arm64.c ${JPEG_DIR}/simd/jsimd_arm64_neon.S)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
	list(APPEND JPEG_SRC ${JPEG_DIR}/simd/jsimd_arm.c ${JPEG_DIR}/simd/jsimd_arm_neon.S)
else()
	list(APPEND JPEG_SRC ${JPEG_DIR}/jsimd_none.c)
endif()

add_library(jpeg-turbo1500 SHARED ${JPEG_SRC})
target_include_directories(jpeg-turbo1500 PUBLIC
	${JPEG_DIR}
	${JPEG_DIR}/include
	${JPEG_DIR}/simd
)
target_compile_definitions(jpeg-turbo1500 PRIVATE ANDROID_NDK)
target_compile_options(jpeg-turbo1500 PRIVATE -Wno-incompatible-pointer-types)
# Original Android.mk setzte SIZEOF_SIZE_T pro ABI (4 fuer 32-bit, 8 fuer 64-bit) im
# SIMD-Zweig; da jsimd_none.c genutzt wird lief dieser Zweig nicht mehr, jchuff.c
# braucht das Define aber trotzdem direkt (siehe #error "Cannot determine word size").
if(CMAKE_SIZEOF_VOID_P EQUAL 8)
	target_compile_definitions(jpeg-turbo1500 PRIVATE SIZEOF_SIZE_T=8)
else()
	target_compile_definitions(jpeg-turbo1500 PRIVATE SIZEOF_SIZE_T=4)
endif()
# Original Android.mk: LOCAL_ARM_MODE := arm + LOCAL_ASMFLAGS += -DELF fuer die
# NEON-.S-Dateien (arm64 hat keinen Thumb/ARM-Modus-Unterschied mehr, -DELF gilt
# aber fuer beide, da die .S-Dateien ueber ELF-Sektions-Makros verzweigen).
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
	set_source_files_properties(
		${JPEG_DIR}/simd/jsimd_arm64_neon.S
		${JPEG_DIR}/simd/jsimd_arm_neon.S
		PROPERTIES COMPILE_FLAGS "-DELF"
	)
endif()

# ---------------------------------------------------------------------------
# 2) libusb100 (aus libusb/android/jni/libusb.mk)
# ---------------------------------------------------------------------------
set(LIBUSB_DIR ${JNI_DIR}/libusb)

set(LIBUSB_SRC
	${LIBUSB_DIR}/libusb/core.c
	${LIBUSB_DIR}/libusb/descriptor.c
	${LIBUSB_DIR}/libusb/hotplug.c
	${LIBUSB_DIR}/libusb/io.c
	${LIBUSB_DIR}/libusb/sync.c
	${LIBUSB_DIR}/libusb/strerror.c
	${LIBUSB_DIR}/libusb/os/android_usbfs.c
	${LIBUSB_DIR}/libusb/os/poll_posix.c
	${LIBUSB_DIR}/libusb/os/threads_posix.c
	${LIBUSB_DIR}/libusb/os/android_netlink.c
)

add_library(usb100 SHARED ${LIBUSB_SRC})
target_include_directories(usb100 PUBLIC
	${LIBUSB_DIR}
	${LIBUSB_DIR}/libusb
)
target_include_directories(usb100 PRIVATE
	${LIBUSB_DIR}/libusb/os
	${LIBUSB_DIR}/android
	# libusb/android/config.h inkludiert utilbase.h, das im Original per
	# LOCAL_C_INCLUDES vom uebergeordneten UVCCamera-Modul (JNI_DIR) bereitgestellt
	# wurde. Ohne diesen Include-Pfad: "fatal error: 'utilbase.h' file not found".
	${JNI_DIR}
)
target_compile_definitions(usb100 PRIVATE ANDROID_NDK ACCESS_RAW_DESCRIPTORS)
target_compile_options(usb100 PRIVATE -O3 -fstrict-aliasing)
target_link_libraries(usb100 log)

# ---------------------------------------------------------------------------
# 3) uvc (aus libuvc/android/jni/Android.mk)
# ---------------------------------------------------------------------------
set(LIBUVC_DIR ${JNI_DIR}/libuvc)

set(LIBUVC_SRC
	${LIBUVC_DIR}/src/ctrl.c
	${LIBUVC_DIR}/src/device.c
	${LIBUVC_DIR}/src/diag.c
	${LIBUVC_DIR}/src/frame.c
	${LIBUVC_DIR}/src/frame-mjpeg.c
	${LIBUVC_DIR}/src/init.c
	${LIBUVC_DIR}/src/stream.c
)

add_library(uvc SHARED ${LIBUVC_SRC})
target_include_directories(uvc PUBLIC
	${LIBUVC_DIR}
	${LIBUVC_DIR}/include
	${LIBUVC_DIR}/include/libuvc
	# libuvc_config.h inkludiert ebenfalls utilbase.h, siehe Kommentar bei usb100.
	${JNI_DIR}
)
target_compile_definitions(uvc PRIVATE ANDROID_NDK LOG_NDEBUG UVC_DEBUGGING)
target_link_libraries(uvc log jpeg-turbo1500 usb100)

# ---------------------------------------------------------------------------
# 4) UVCCamera (aus UVCCamera/Android.mk)
# ---------------------------------------------------------------------------
set(UVCCAMERA_DIR ${JNI_DIR}/UVCCamera)

set(UVCCAMERA_SRC
	${UVCCAMERA_DIR}/_onload.cpp
	${UVCCAMERA_DIR}/utilbase.cpp
	${UVCCAMERA_DIR}/UVCCamera.cpp
	${UVCCAMERA_DIR}/UVCPreview.cpp
	${UVCCAMERA_DIR}/UVCButtonCallback.cpp
	${UVCCAMERA_DIR}/UVCStatusCallback.cpp
	${UVCCAMERA_DIR}/Parameters.cpp
	${UVCCAMERA_DIR}/serenegiant_usb_UVCCamera.cpp
)

add_library(UVCCamera SHARED ${UVCCAMERA_SRC})
target_include_directories(UVCCamera PRIVATE
	${UVCCAMERA_DIR}
	${JNI_DIR}
	${JNI_DIR}/rapidjson/include
	${LIBUVC_DIR}/include
	${LIBUVC_DIR}/include/libuvc
)
target_compile_definitions(UVCCamera PRIVATE ANDROID_NDK LOG_NDEBUG ACCESS_RAW_DESCRIPTORS)
target_compile_options(UVCCamera PRIVATE -O3 -fstrict-aliasing)
target_link_libraries(UVCCamera log android dl uvc usb100)
