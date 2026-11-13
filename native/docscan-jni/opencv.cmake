# Initial cache for the OpenCV build behind libwmdocscan (cmake -C), shared
# by build.sh and the desktop check so both build the same OpenCV. Only the
# core, imgproc, flann and geometry modules, as static libraries, and nothing that downloads
# a binary or reaches outside the source tree: no IPP, FastCV, ITT, OpenCL,
# Eigen, LAPACK or TBB. KleidiCV is off here too; build.sh turns it on for
# arm64 with a source tree it fetched and checked itself. Carotene (in-tree NEON code) is off too:
# OpenCV's own universal intrinsics cover the few calls the scanner makes,
# and it is ~200 KB smaller without.
# OpenCV 5 moved the contour and shape calls the scanner makes (contourArea,
# approxPolyDP, convexHull, getPerspectiveTransform) out of imgproc into
# geometry, which needs flann.
set(BUILD_LIST "core,imgproc,flann,geometry" CACHE STRING "")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "")
set(CMAKE_BUILD_TYPE MinSizeRel CACHE STRING "")

foreach(off
        BUILD_TESTS BUILD_PERF_TESTS BUILD_EXAMPLES BUILD_DOCS BUILD_opencv_apps
        BUILD_ANDROID_PROJECTS BUILD_ANDROID_EXAMPLES BUILD_ANDROID_SERVICE
        BUILD_JAVA BUILD_FAT_JAVA_LIB BUILD_KOTLIN_EXTENSIONS BUILD_OBJC
        BUILD_opencv_python2 BUILD_opencv_python3 BUILD_opencv_js
        BUILD_TIFF BUILD_OPENJPEG BUILD_JASPER BUILD_JPEG BUILD_PNG BUILD_WEBP BUILD_OPENEXR
        BUILD_TBB BUILD_IPP_IW BUILD_ITT BUILD_PROTOBUF
        WITH_CAROTENE WITH_KLEIDICV WITH_FASTCV WITH_NDSRVP WITH_HAL_RVV
        WITH_IPP WITH_ITT WITH_OPENCL WITH_OPENCL_SVM WITH_OPENCLAMDFFT WITH_OPENCLAMDBLAS
        WITH_OPENCL_D3D11_NV WITH_VA WITH_VA_INTEL WITH_EIGEN WITH_LAPACK WITH_TBB
        WITH_OPENMP WITH_PROTOBUF WITH_FLATBUFFERS WITH_ADE WITH_OBSENSOR WITH_QUIRC
        WITH_ANDROID_MEDIANDK WITH_ANDROID_NATIVE_CAMERA WITH_CUDA WITH_VULKAN
        WITH_1394 WITH_FFMPEG WITH_GSTREAMER WITH_V4L WITH_AVFOUNDATION WITH_GTK WITH_QT
        WITH_JPEG WITH_PNG WITH_TIFF WITH_WEBP WITH_OPENJPEG WITH_JASPER WITH_OPENEXR
        WITH_IMGCODEC_GIF WITH_IMGCODEC_HDR WITH_IMGCODEC_SUNRASTER WITH_IMGCODEC_PXM WITH_IMGCODEC_PFM
        CV_TRACE OPENCV_GENERATE_PKGCONFIG OPENCV_ENABLE_NONFREE ENABLE_CCACHE)
    set(${off} OFF CACHE BOOL "")
endforeach()

# Only the baseline instruction set: no extra NEON dot-product copies of
# each kernel, which would double their size.
set(CPU_DISPATCH "" CACHE STRING "")

# Nothing about the machine or the moment goes into the build information
# string, though the linker drops it anyway (nothing calls
# cv::getBuildInformation).
set(BUILD_INFO_SKIP_TIMESTAMP ON CACHE BOOL "")
set(BUILD_INFO_SKIP_SYSTEM_VERSION ON CACHE BOOL "")
