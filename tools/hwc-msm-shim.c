// SPDX-License-Identifier: MIT
/*
 * libhwc_msm_shim.so: LD_PRELOADed into the emulator's HWC3 only
 * (vendor/init.bullhead.rc). The ranchu composer takes the first
 * /dev/dri/card* whose driver name contains "virtio"; in its "client" mode
 * it only uses plain KMS (atomic commit of SurfaceFlinger's composed frame),
 * which the msm MDP5 does too. So tell it the msm device is "virtio_gpu".
 * Nothing else is changed, and no other process sees this.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

typedef struct _drmVersion {
	int version_major;
	int version_minor;
	int version_patchlevel;
	int name_len;
	char *name;
	int date_len;
	char *date;
	int desc_len;
	char *desc;
} drmVersion, *drmVersionPtr;

drmVersionPtr drmGetVersion(int fd)
{
	static drmVersionPtr (*real)(int);
	drmVersionPtr v;

	if (!real)
		real = (drmVersionPtr (*)(int))dlsym(RTLD_NEXT, "drmGetVersion");
	if (!real)
		return NULL;
	v = real(fd);
	if (v && v->name && strcmp(v->name, "msm") == 0) {
		char *n = strdup("virtio_gpu");
		if (n) {
			free(v->name);	/* libdrm's drmFreeVersion() free()s it */
			v->name = n;
			v->name_len = (int)strlen(n);
		}
	}
	return v;
}
