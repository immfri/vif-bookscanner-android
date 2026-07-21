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
#  Bewusste Vereinfachung (Phase 1: nur Bau-Faehigkeit, keine Treiber-
#  Optimierung): die SIMD-Assembler-Pfade von libjpeg-turbo (ARM-NEON .S,
#  x86 .asm) wurden NICHT auf CMake portiert, weil sie einen eigenen
#  Assembler (yasm/nasm bzw. GNU-Assembler-Direktiven) voraussetzen, den
#  ndk-build automatisch mitgebracht hat, CMake/Clang so aber nicht ohne
#  weiteres Setup. Stattdessen wird ueberall jsimd_none.c verwendet
#  (reines C, keine SIMD-Beschleunigung beim JPEG-Decoding). Funktional
#  identisch, nur langsamer. Spaeter nachruestbar.
#
cmake_minimum_required(VERSION 3.22.1)
project(uvccamera)

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
	${JPEG_DIR}/jsimd_none.c
)

add_library(jpeg-turbo1500 SHARED ${JPEG_SRC})
target_include_directories(jpeg-turbo1500 PUBLIC
	${JPEG_DIR}
	${JPEG_DIR}/include
	${JPEG_DIR}/simd
)
target_compile_definitions(jpeg-turbo1500 PRIVATE ANDROID_NDK)
target_compile_options(jpeg-turbo1500 PRIVATE -Wno-incompatible-pointer-types)

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
