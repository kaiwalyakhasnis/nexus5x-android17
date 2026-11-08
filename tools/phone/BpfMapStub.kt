// SPDX-License-Identifier: MIT
// bpf-map-stub PATH KEY_SIZE VALUE_SIZE MAX_ENTRIES: create an empty BPF hash
// map and pin it at PATH (mode 0444), unless something is pinned there.
//
// Android 17's libmeminfo reads GPU memory use from the map that
// /system/etc/bpf/gpuMem.bpf creates, and aborts its caller (system_server)
// when the map is missing. gpuMem.bpf attaches to the gpu_mem/gpu_mem_total
// tracepoint, which only Android's common kernel has, so it does not load
// here. An empty map with the same key/value sizes makes GPU memory read as 0
// instead. Run by the ramdisk's /init once Android has mounted /sys/fs/bpf.
@file:OptIn(ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import platform.posix.*

// include/uapi/linux/bpf.h
private const val BPF_MAP_CREATE = 0L
private const val BPF_OBJ_PIN = 6L
private const val BPF_MAP_TYPE_HASH = 1
private const val ATTR_SIZE = 128       // union bpf_attr, zero-padded

private fun bpf(cmd: Long, attr: CPointer<ByteVar>): Long =
    sys(NR_BPF, cmd, attr.toLong(), ATTR_SIZE.toLong())

private fun CPointer<ByteVar>.u32(at: Int, v: Int) {
    (this + at)!!.reinterpret<IntVar>().pointed.value = v
}

private fun CPointer<ByteVar>.u64(at: Int, v: Long) {
    (this + at)!!.reinterpret<LongVar>().pointed.value = v
}

fun bpfMapStub(args: List<String>): Int {
    if (args.size != 4) {
        eprintln("usage: bpf-map-stub PATH KEY_SIZE VALUE_SIZE MAX_ENTRIES")
        return 2
    }
    val (path, key, value, entries) = args
    if (access(path, F_OK) == 0) return 0   // already there (the real one, or ours)

    memScoped {
        val attr = allocArray<ByteVar>(ATTR_SIZE)
        memset(attr, 0, ATTR_SIZE.convert())
        attr.u32(0, BPF_MAP_TYPE_HASH)          // map_type
        attr.u32(4, key.toInt())                // key_size
        attr.u32(8, value.toInt())              // value_size
        attr.u32(12, entries.toInt())           // max_entries
        val fd = bpf(BPF_MAP_CREATE, attr)
        if (fd < 0) return fail("BPF_MAP_CREATE")

        memset(attr, 0, ATTR_SIZE.convert())
        attr.u64(0, path.cstr.ptr.toLong())     // pathname
        attr.u32(8, fd.toInt())                 // bpf_fd
        if (bpf(BPF_OBJ_PIN, attr) < 0) return fail("BPF_OBJ_PIN")
    }
    chmod(path, "444".toUInt(8))
    return 0
}
