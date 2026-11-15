"""Makes ExecuTorch's Vulkan shader build reproducible (build.sh).

    patch_vulkan_spv.py EXECUTORCH_SRC

backends/vulkan/runtime/gen_vulkan_spv.py finds its GLSL sources and shader
YAML files with glob.glob, which returns them in the order the file system
lists the directory. That order picks the order of the shaders in the
generated registry (spv.cpp), and with it the bytes of
libwmexecutorch_vulkan.so, so two machines building the same commit got two
different libraries: 0.6.7's arm64 F-Droid build failed its reproducibility
check on exactly that file. Every glob is sorted here, so the order is the
same everywhere.

Skipped when it is already in place, so a reused tree is fine.
"""
import sys

PATH = f"{sys.argv[1]}/backends/vulkan/runtime/gen_vulkan_spv.py"
HELPER = "\n\ndef _sorted_glob(*args, **kwargs):\n    return sorted(glob.glob(*args, **kwargs))\n"

s = open(PATH).read()
if "_sorted_glob" not in s:
    calls = s.count("glob.glob(")
    assert calls == 3, f"gen_vulkan_spv.py: expected 3 glob.glob calls, found {calls}"
    s = s.replace("glob.glob(", "_sorted_glob(")
    anchor = "\nimport glob\n"
    assert s.count(anchor) == 1, "gen_vulkan_spv.py: `import glob` not found once"
    s = s.replace(anchor, anchor + HELPER, 1)
    open(PATH, "w").write(s)
