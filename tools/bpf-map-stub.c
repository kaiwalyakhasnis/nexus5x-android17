// SPDX-License-Identifier: MIT
/*
 * bpf-map-stub PATH KEY_SIZE VALUE_SIZE MAX_ENTRIES: create an empty BPF hash
 * map and pin it at PATH (mode 0444), unless something is pinned there.
 *
 * Android 17's libmeminfo reads GPU memory use from the map that
 * /system/etc/bpf/gpuMem.bpf creates, and aborts its caller (system_server)
 * when the map is missing. gpuMem.bpf attaches to the gpu_mem/gpu_mem_total
 * tracepoint, which only Android's common kernel has, so it does not load
 * here. An empty map with the same key/value sizes makes GPU memory read as 0
 * instead. Run by the ramdisk's /init once Android has mounted /sys/fs/bpf.
 */
#include <errno.h>
#include <linux/bpf.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static long bpf(int cmd, union bpf_attr *attr)
{
	return syscall(__NR_bpf, cmd, attr, sizeof(*attr));
}

int main(int argc, char **argv)
{
	union bpf_attr attr;
	struct stat st;
	int fd;

	if (argc != 5) {
		fprintf(stderr, "usage: %s PATH KEY_SIZE VALUE_SIZE MAX_ENTRIES\n", argv[0]);
		return 2;
	}
	if (stat(argv[1], &st) == 0)
		return 0;	/* already there (the real one, or ours) */

	memset(&attr, 0, sizeof(attr));
	attr.map_type = BPF_MAP_TYPE_HASH;
	attr.key_size = atoi(argv[2]);
	attr.value_size = atoi(argv[3]);
	attr.max_entries = atoi(argv[4]);
	fd = bpf(BPF_MAP_CREATE, &attr);
	if (fd < 0) {
		perror("BPF_MAP_CREATE");
		return 1;
	}
	memset(&attr, 0, sizeof(attr));
	attr.pathname = (unsigned long)argv[1];
	attr.bpf_fd = fd;
	if (bpf(BPF_OBJ_PIN, &attr) < 0) {
		perror("BPF_OBJ_PIN");
		return 1;
	}
	chmod(argv[1], 0444);
	return 0;
}
