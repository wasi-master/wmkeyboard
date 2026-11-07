"""Patches ExecuTorch's MediaTek backend for libwmexecutorch_neuron (build.sh).

    patch_neuron.py EXECUTORCH_SRC

- neuron_backend is built static, not shared with a runtime of its own, and
  left out of the install and of executorch_backends, so the JNI step links
  its archive straight out of the build tree into one runtime (as QNN's).
- Its buffer allocator becomes optional. NeuronBufferAllocator.cpp sets the
  allocator up from a static initialiser that calls neuron_buffer_allocator_create
  through a null pointer when libneuron_buffer_allocator.so (from MediaTek's
  SDK, not on phones) is missing, so loading the library would kill the
  process. Without the allocator the backend passes buffers by copy, the
  path it already takes for memory the allocator did not hand out.

Each patch is skipped when it is already in place, so a reused tree is fine.
"""
import sys

ROOT = sys.argv[1]


def patch(rel, old, new):
    path = f"{ROOT}/{rel}"
    s = open(path).read()
    if old not in s and (not new or new in s):
        return
    assert s.count(old) == 1, f"{rel}: {old[:60]!r} not found once"
    open(path, "w").write(s.replace(old, new, 1))


patch("backends/mediatek/CMakeLists.txt",
      "add_library(neuron_backend SHARED)", "add_library(neuron_backend STATIC)")
patch("backends/mediatek/CMakeLists.txt",
      "install(\n  TARGETS neuron_backend\n  EXPORT ExecuTorchTargets\n  DESTINATION ${CMAKE_INSTALL_LIBDIR}\n)\n", "")
patch("CMakeLists.txt", "  list(APPEND _executorch_backends neuron_backend)\n", "")
patch("backends/mediatek/runtime/NeuronBufferAllocator.cpp",
      "  if (handle == nullptr) {\n    loadLibrary();\n    if (allocatorHandle == nullptr) {",
      "  if (handle == nullptr) {\n    if (loadLibrary() && allocatorHandle == nullptr) {")
patch("backends/mediatek/runtime/NeuronBufferAllocator.cpp",
      "namespace {\nstatic auto& singletonInstance = GET_NEURON_ALLOCATOR;\n} // namespace\n", "")
# The allocator's calls, for the phone without its library: nothing found,
# nothing allocated (shared weights then fail to load, as they must).
ALLOC = "backends/mediatek/runtime/NeuronBufferAllocator.cpp"
patch(ALLOC, "  return allocate_func(allocatorHandle, size);",
      "  return allocate_func && allocatorHandle ? allocate_func(allocatorHandle, size) : nullptr;")
patch(ALLOC, "  return remove_func(allocatorHandle, address);",
      "  return remove_func && allocatorHandle ? remove_func(allocatorHandle, address) : false;")
patch(ALLOC, "  return static_cast<const MemoryUnit*>(find_func(allocatorHandle, address));",
      "  return find_func && allocatorHandle\n      ? static_cast<const MemoryUnit*>(find_func(allocatorHandle, address))\n      : nullptr;")
patch(ALLOC, "  clear_func(allocatorHandle);",
      "  if (clear_func && allocatorHandle) clear_func(allocatorHandle);")
