# What the Vulkan builds of llama.cpp and whisper.cpp compile against, shared
# by native/llamacpp-jni/build.sh and native/whispercpp-jni/build.sh. Sourced,
# not run: they call vulkan_setup with their scratch directory, after which
# VULKAN_CMAKE holds the CMake arguments a WM_GPU=vulkan configuration needs.
#
# - Khronos's Vulkan-Headers (vulkan.hpp, which the NDK lacks) and
#   SPIRV-Headers, at pinned commits.
# - glslc, built here for this machine from shaderc at a pinned release with
#   the glslang, SPIRV-Tools and SPIRV-Headers commits its DEPS file names.
#   The NDK's own glslc (shaderc 2022.3) is too old for the shader extensions
#   ggml's newer kernels use (integer dot product, cooperative matrix), and
#   compiling every shader with one pinned glslc keeps the SPIR-V, and so the
#   library, the same from machine to machine. GLSLC overrides it.
# - The NDK's libvulkan.so stub to link to; the phone's loader at run time.
#
# Needs git, CMake, Ninja and a host C++ compiler. The caller's retry and
# fresh_clone functions are used for the network.

VULKAN_HEADERS_TAG=vulkan-sdk-1.4.363.0
VULKAN_HEADERS_COMMIT=6802bb4733b63ed5efd3adb308a6c885ef180ea1
SPIRV_HEADERS_TAG=vulkan-sdk-1.4.363.0
SPIRV_HEADERS_COMMIT=496543121ce6419f23d6fa5d7194ba66c36212d2
SHADERC_TAG=v2026.4
SHADERC_COMMIT=b64fd42c4a2a9d501c47aca5d3571f7673a5e9cc
# shaderc v2026.4's DEPS.
SHADERC_GLSLANG_COMMIT=e1b562a8bed273a02f30b59b66a5d499793cede5
SHADERC_SPIRV_TOOLS_COMMIT=ef96ed763b43b59b33b31b362f09a02b729fa1c9
SHADERC_SPIRV_HEADERS_COMMIT=04fd3caa1e8267e4d95c806cad901181728e1006

# dir, repository URL, commit: a shallow fetch of exactly that commit.
vulkan_fetch_commit() {
    local dir=$1 url=$2 commit=$3
    rm -rf "$dir" && git init -q "$dir" &&
        git -C "$dir" fetch -q --depth 1 "$url" "$commit" &&
        git -C "$dir" checkout -q FETCH_HEAD
}

# work directory, NDK llvm bin directory
vulkan_setup() {
    local work=$1 bin=$2

    local headers=${VULKAN_HEADERS_SRC:-}
    if [ -z "$headers" ]; then
        headers=$work/Vulkan-Headers
        retry vulkan_fetch_commit "$headers" https://github.com/KhronosGroup/Vulkan-Headers "$VULKAN_HEADERS_COMMIT"
    fi
    [ "$(git -C "$headers" rev-parse HEAD)" = "$VULKAN_HEADERS_COMMIT" ] || {
        echo "Vulkan-Headers is not at $VULKAN_HEADERS_COMMIT" >&2; return 1; }

    local spirv=${SPIRV_HEADERS_SRC:-}
    if [ -z "$spirv" ]; then
        spirv=$work/SPIRV-Headers
        retry vulkan_fetch_commit "$spirv" https://github.com/KhronosGroup/SPIRV-Headers "$SPIRV_HEADERS_COMMIT"
    fi
    [ "$(git -C "$spirv" rev-parse HEAD)" = "$SPIRV_HEADERS_COMMIT" ] || {
        echo "SPIRV-Headers is not at $SPIRV_HEADERS_COMMIT" >&2; return 1; }
    # Laid out like the Vulkan SDK, one include directory with both sets of
    # headers in it: ggml includes spirv/unified1/spirv.hpp without linking
    # SPIRV-Headers' target, so only Vulkan's include path reaches it. The
    # CMake package (find_package(SPIRV-Headers)) comes along in share/.
    local sdk=$work/vulkan-sdk
    cmake -S "$spirv" -B "$work/spirv-build" -G Ninja -DCMAKE_INSTALL_PREFIX="$sdk" \
        -DSPIRV_HEADERS_ENABLE_TESTS=OFF -DSPIRV_HEADERS_ENABLE_INSTALL=ON > "$work/spirv.log" 2>&1 \
        && cmake --install "$work/spirv-build" >> "$work/spirv.log" 2>&1 \
        || { tail -30 "$work/spirv.log"; return 1; }
    cp -R "$headers/include/." "$sdk/include/"

    local glslc=${GLSLC:-}
    if [ -z "$glslc" ]; then
        local sc=$work/shaderc
        retry vulkan_fetch_commit "$sc" https://github.com/google/shaderc "$SHADERC_COMMIT"
        retry vulkan_fetch_commit "$sc/third_party/glslang" https://github.com/KhronosGroup/glslang "$SHADERC_GLSLANG_COMMIT"
        retry vulkan_fetch_commit "$sc/third_party/spirv-tools" https://github.com/KhronosGroup/SPIRV-Tools "$SHADERC_SPIRV_TOOLS_COMMIT"
        retry vulkan_fetch_commit "$sc/third_party/spirv-headers" https://github.com/KhronosGroup/SPIRV-Headers "$SHADERC_SPIRV_HEADERS_COMMIT"
        # A host tool: no Android toolchain, nothing but glslc itself. The
        # install rules stay configured, though nothing installs: shaderc
        # v2026.4 leaves glslang's on whatever SHADERC_SKIP_INSTALL says, and
        # its export set then needs SPIRV-Tools' to exist as well.
        cmake -S "$sc" -B "$work/shaderc-build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
            -DSHADERC_SKIP_TESTS=ON -DSHADERC_SKIP_EXAMPLES=ON -DSHADERC_SKIP_COPYRIGHT_CHECK=ON \
            -DSPIRV_SKIP_TESTS=ON -DSPIRV_SKIP_EXECUTABLES=ON -DENABLE_GLSLANG_BINARIES=OFF \
            -DSPIRV_TOOLS_BUILD_STATIC=ON > "$work/shaderc.log" 2>&1 \
            && cmake --build "$work/shaderc-build" --target glslc_exe >> "$work/shaderc.log" 2>&1 \
            || { tail -40 "$work/shaderc.log"; return 1; }
        glslc=$work/shaderc-build/glslc/glslc
    fi
    [ -x "$glslc" ] || { echo "no glslc at $glslc" >&2; return 1; }

    # The NDK's sysroot sits beside its bin directory. Android 9's stub (API
    # 28), not the library's minimum 24: ggml calls Vulkan 1.1 functions such
    # as vkGetPhysicalDeviceFeatures2 directly, and Android 7's loader exports
    # 1.0 alone. Only phones with Vulkan 1.2 load these builds (PhoneGpu), and
    # their loader exports every 1.1 function.
    local stub
    stub=$(cd "$bin/.." && pwd -P)/sysroot/usr/lib/aarch64-linux-android/28/libvulkan.so
    [ -f "$stub" ] || { echo "no libvulkan.so stub at $stub" >&2; return 1; }

    # For builds that want the shader compiler alone (ExecuTorch's Vulkan backend).
    VULKAN_GLSLC=$glslc
    VULKAN_CMAKE=(
        -DVulkan_INCLUDE_DIR="$sdk/include" -DVulkan_LIBRARY="$stub"
        -DVulkan_GLSLC_EXECUTABLE="$glslc" -DSPIRV-Headers_DIR="$sdk/share/cmake/SPIRV-Headers"
        -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH
    )
}
