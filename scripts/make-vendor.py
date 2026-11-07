#!/usr/bin/env python3
"""out/vendor.img: the Android 17 emulator's vendor image, adapted to bullhead.

The GSI is only /system; a device brings its own /vendor (HALs, SELinux
policy, init scripts). bullhead's own is Android 8.1-era and speaks 3.10 CAF
kernel interfaces, so the base here is the vendor partition of Google's
Android 17 arm64 emulator image (same SELinux version, 202604, as the GSI).
It is edited in place with debugfs (no root, no loop mount), and every file
added gets the SELinux label Android's file_contexts give its path:

  /vendor/etc/init/hw/init.bullhead.rc   (vendor/init.bullhead.rc)
  /vendor/etc/fstab.bullhead             (vendor/fstab.bullhead)
  /vendor/etc/adb_keys                   this PC's ~/.android/adbkey.pub
  /vendor/lib/modules/<kver>/            the =m kernel modules + modules.load
  /vendor/etc/selinux/vendor_sepolicy.cil  + typepermissive for every domain
  (precompiled_sepolicy removed: init compiles the policy at boot)

Usage: make-vendor.py  (paths are relative to the project)
"""
import os, re, shutil, subprocess, sys, tempfile

TOP = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EMU = os.path.join(TOP, 'downloads/emu/extracted/arm64-v8a')
GSI = os.path.join(TOP, 'downloads/gsi/extracted/system.img')
OUT = os.path.join(TOP, 'out')
SIZE = os.environ.get('VENDOR_SIZE', '384M')


def run(*cmd, **kw):
    return subprocess.run(cmd, check=True, **kw)


def fsck(img):
    # 0: clean, 1: errors fixed; anything else is a real problem
    r = subprocess.run(['e2fsck', '-fy', img], stdout=subprocess.DEVNULL)
    if r.returncode > 1:
        raise SystemExit(f'e2fsck {img}: exit {r.returncode}')


def debugfs_read(img, path):
    with tempfile.NamedTemporaryFile() as t:
        run('debugfs', '-R', f'dump {path} {t.name}', img, stderr=subprocess.DEVNULL)
        return open(t.name, 'rb').read()


def gpt_partition(disk, name):
    """(offset, size) in bytes of a GPT partition in an emulator disk image."""
    import struct
    with open(disk, 'rb') as f:
        f.seek(512)
        h = f.read(92)
        assert h[:8] == b'EFI PART', disk
        lba, n, sz = struct.unpack('<QII', h[72:88])
        f.seek(lba * 512)
        for _ in range(n):
            e = f.read(sz)
            first, last = struct.unpack('<QQ', e[32:48])
            if e[56:128].decode('utf-16le').rstrip('\0') == name:
                return first * 512, (last - first + 1) * 512
    raise SystemExit(f'no {name} partition in {disk}')


# Emulator HALs that need emulator hardware (virtio sensors/modem/GNSS,
# goldfish camera and codecs, virtio Wi-Fi/Bluetooth): declared in the VINTF
# manifest, their clients wait for them forever (system_server blocks on
# ISensors/default). Drop their manifests, services and the features they'd
# advertise, until bullhead has real ones.
REMOVE = [
    'etc/vintf/manifest/android.hardware.sensors-multihal.xml',
    'etc/init/android.hardware.sensors-service-multihal.rc',
    'etc/vintf/manifest/android.hardware.radio.ranchu.xml',
    'etc/init/android.hardware.radio.ranchu.rc',
    'etc/vintf/manifest/android.hardware.gnss-service.ranchu.xml',
    'etc/init/android.hardware.gnss-service.ranchu.rc',
    'etc/vintf/manifest/android.hardware.camera.provider.ranchu.xml',
    'etc/vintf/manifest/android.hardware.camera.provider@2.7-service-google.xml',
    'etc/init/android.hardware.camera.provider.ranchu.rc',
    'etc/init/android.hardware.camera.provider.ranchu_minigbm.rc',
    'etc/init/android.hardware.camera.provider@2.7-service-google.rc',
    'etc/vintf/manifest/android.hardware.biometrics.fingerprint-service.ranchu.xml',
    'etc/init/android.hardware.biometrics.fingerprint-service.ranchu.rc',
    'etc/vintf/manifest/android.hardware.media.c2-service-goldfish.xml',
    'etc/init/android.hardware.media.c2-service-goldfish.rc',
    'etc/vintf/manifest/android.hardware.wifi-service.xml',
    'etc/vintf/manifest/android.hardware.wifi.hostapd.xml',
    'etc/vintf/manifest/android.hardware.wifi.supplicant.xml',
    'etc/init/android.hardware.wifi-service.rc',
    'etc/init/hostapd.android.rc',
    'etc/vintf/manifest/bluetooth-service-default.xml',
    'etc/vintf/manifest/bluetooth_audio.xml',
    'etc/init/bluetooth-service-default.rc',
    # vendor APEXes for emulator-only hardware: ContextHub (system_server's
    # ContextHubSystemService throws at boot phase 500), UWB (/dev/hvc2), and
    # the Thread network radio simulator
    'apex/com.android.hardware.contexthub.apex',
    'apex/com.android.hardware.uwb.apex',
    'apex/com.android.hardware.threadnetwork-simulation-rcp.apex',
] + ['etc/permissions/' + f for f in (
    'android.hardware.bluetooth_le.xml', 'android.hardware.bluetooth.xml',
    'android.hardware.camera.concurrent.prebuilt.xml',
    'android.hardware.camera.flash-autofocus.prebuilt.xml',
    'android.hardware.camera.front.prebuilt.xml',
    'android.hardware.camera.full.prebuilt.xml',
    'android.hardware.camera.raw.prebuilt.xml',
    'android.hardware.fingerprint.prebuilt.xml', 'android.hardware.uwb.xml',
    'android.hardware.vulkan.compute.xml', 'android.hardware.vulkan.level.xml',
    'android.hardware.vulkan.version.xml', 'android.software.vulkan.deqp.level.xml',
    'android.hardware.opengles.aep.xml',
    'android.hardware.wifi.direct.xml', 'android.hardware.wifi.passpoint.xml',
    'android.hardware.wifi.xml')]


# The emulator's gralloc and hwcomposer only know virtio-gpu. Same-length
# string edits make them take the Adreno/MDP5 msm device instead:
#  - minigbm: "vkms" (one of its dumb-buffer drivers) becomes "msm", so msm
#    buffers are dumb buffers (linear, from the VRAM carveout; kernel patch
#    0003 allows them on the render node minigbm opens)
#  - ranchu HWC3: it looks for a DRM device named "virtio_gpu"; in "client"
#    mode it only puts SurfaceFlinger's composed frame on a KMS plane
BINARY_PATCHES = [
    ('/lib64/libminigbm_gralloc.so', [(b'\0vkms\0', b'\0msm\0\0')]),
    ('/bin/hw/android.hardware.graphics.composer3-service.ranchu',
     [(b'\0virtio_gpu\0', b'\0msm\0\0\0\0\0\0\0\0')]),
]


def debugfs_label(img, path):
    out = subprocess.run(['debugfs', '-R', f'ea_get {path} security.selinux', img],
                         capture_output=True, text=True).stdout.strip()
    m = re.search(r'(u:object_r:[^\s"\\]+)', out)
    assert m, (path, out)
    return m.group(1)


class Labeler:
    """file_contexts lookup: the last matching line wins (as libselinux)."""
    def __init__(self, *texts):
        self.rules = []
        for text in texts:
            for line in text.splitlines():
                p = line.split()
                if len(p) < 2 or p[0].startswith('#'):
                    continue
                if not (p[0].startswith('/vendor') or p[0].startswith('/(vendor|system/vendor)')):
                    continue
                if len(p) == 3:   # "-d", "--" file-type selectors: ignore the type
                    p = [p[0], p[2]]
                self.rules.append((re.compile(p[0] + r'\Z'), p[1]))

    def __call__(self, path):
        label = None
        for rx, ctx in self.rules:
            if rx.match(path):
                label = ctx
        return label or 'u:object_r:vendor_file:s0'


def permissive_cil(plat_cil, vendor_cil):
    """typepermissive for every type in the 'domain' attribute (plat + vendor)."""
    def domain_members(cil):
        out = set()
        for m in re.finditer(r'^\(typeattributeset domain \((.*)\)\)\s*$', cil, re.M):
            out.update(m.group(1).split())
        return out
    types = set(re.findall(r'^\(type (\S+)\)', plat_cil, re.M)) | \
        set(re.findall(r'^\(type (\S+)\)', vendor_cil, re.M))
    already = set(re.findall(r'^\(typepermissive (\S+)\)', plat_cil + vendor_cil, re.M))
    doms = sorted((domain_members(plat_cil) | domain_members(vendor_cil)) & types - already)
    return doms


def main():
    os.makedirs(OUT, exist_ok=True)
    img = os.path.join(OUT, 'vendor.img')
    kout = os.path.join(OUT, 'kernel')
    kver = open(os.path.join(kout, 'include/config/kernel.release')).read().strip()

    # 1. the emulator's vendor partition, grown (room for modules, Mesa later)
    off, size = gpt_partition(os.path.join(EMU, 'vendor.img'), 'vendor')
    with open(os.path.join(EMU, 'vendor.img'), 'rb') as src, open(img + '.tmp', 'wb') as dst:
        src.seek(off)
        left = size
        while left:
            b = src.read(min(left, 1 << 20))
            dst.write(b)
            left -= len(b)
    fsck(img + '.tmp')
    run('resize2fs', img + '.tmp', SIZE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # Android builds this image with ext4 "shared_blocks": identical blocks
    # of different files are stored once. debugfs doesn't know: rm frees a
    # shared block that other files still use, and the next write lands on
    # them (it corrupted the power/thermal APEXes). Give every file its own
    # blocks first (this also clears the feature).
    r = subprocess.run(['e2fsck', '-fy', '-E', 'unshare_blocks', img + '.tmp'],
                       stdout=subprocess.DEVNULL)
    if r.returncode > 1:
        raise SystemExit(f'e2fsck -E unshare_blocks: exit {r.returncode}')
    os.replace(img + '.tmp', img)

    plat_fc = debugfs_read(GSI, '/system/etc/selinux/plat_file_contexts').decode()
    vendor_fc = debugfs_read(img, '/etc/selinux/vendor_file_contexts').decode()
    label = Labeler(plat_fc, vendor_fc)

    work = tempfile.mkdtemp(prefix='vendor-')
    cmds = []
    labels = {}

    def label_file(ctx):
        if ctx not in labels:
            p = os.path.join(work, f'label{len(labels)}')
            open(p, 'wb').write(ctx.encode() + b'\0')
            labels[ctx] = p
        return labels[ctx]

    def setmeta(vpath, mode, uid=0, gid=0):
        cmds.append(f'set_inode_field {vpath} mode 0{mode:o}')
        cmds.append(f'set_inode_field {vpath} uid {uid}')
        cmds.append(f'set_inode_field {vpath} gid {gid}')
        cmds.append(f'ea_set -f {label_file(label("/vendor" + vpath))} {vpath} security.selinux')

    made_dirs = set()

    def mkdir(vpath):
        parts = vpath.strip('/').split('/')
        for i in range(1, len(parts) + 1):
            d = '/' + '/'.join(parts[:i])
            if d in made_dirs:
                continue
            made_dirs.add(d)
            cmds.append(f'mkdir {d}')
            setmeta(d, 0o40755, 0, 2000)

    def put(host, vpath, mode=0o100644, gid=0):
        cmds.append(f'rm {vpath}')
        cmds.append(f'write {host} {vpath}')
        setmeta(vpath, mode, 0, gid)

    def put_text(text, vpath, mode=0o100644):
        p = os.path.join(work, 'f%d' % len(cmds))
        open(p, 'w').write(text)
        put(p, vpath, mode)

    # 2. our init script, fstab, adb key
    put(os.path.join(TOP, 'vendor/init.bullhead.rc'), '/etc/init/hw/init.bullhead.rc')
    put(os.path.join(TOP, 'vendor/fstab.bullhead'), '/etc/fstab.bullhead')
    key = os.path.expanduser('~/.android/adbkey.pub')
    put(key, '/etc/adb_keys')

    # 3. kernel modules (the ramdisk's /init loads them before Android's init)
    mroot = os.path.join(OUT, 'modules/lib/modules', kver)
    names = []
    for dp, dns, fns in os.walk(mroot):
        dns.sort()
        rel = os.path.relpath(dp, os.path.join(OUT, 'modules'))
        mkdir('/' + rel)
        for fn in sorted(fns):
            if os.path.islink(os.path.join(dp, fn)):
                continue
            put(os.path.join(dp, fn), f'/{rel}/{fn}')
            if fn.endswith('.ko'):
                names.append(fn[:-3])
    put_text('\n'.join(names) + '\n', f'/lib/modules/{kver}/modules.load')

    # 4. graphics: Mesa (scripts/build-mesa.sh) as the EGL/GLES driver, the
    # A420 microcode, and two of the emulator's blobs taught about msm
    mesa = os.path.join(OUT, 'mesa/lib64')
    if os.path.isdir(mesa):
        mkdir('/lib64/egl')
        for fn in sorted(os.listdir(os.path.join(mesa, 'egl'))):
            put(os.path.join(mesa, 'egl', fn), '/lib64/egl/' + fn)
        for fn in sorted(os.listdir(mesa)):
            if fn.endswith('.so'):   # libgallium_dri.so: next to the EGL libs
                put(os.path.join(mesa, fn), '/lib64/egl/' + fn)
    # the HWC3's drmGetVersion() shim (tools/hwc-msm-shim.c, LD_PRELOAD)
    shim = os.path.join(OUT, 'shim/libhwc_msm_shim.so')
    if os.path.exists(shim):
        put(shim, '/lib64/libhwc_msm_shim.so')
    # resource overlays (scripts/build-overlays.sh): circular icons, ...
    ov = os.path.join(OUT, 'overlay')
    if os.path.isdir(ov):
        for fn in sorted(os.listdir(ov)):
            if fn.endswith('.apk'):
                put(os.path.join(ov, fn), '/overlay/' + fn)
    fw = os.path.join(TOP, 'downloads/linux-firmware')
    mkdir('/firmware/qcom')
    for fn in ('a420_pm4.fw', 'a420_pfp.fw', 'LICENSE.qcom'):
        put(os.path.join(fw, fn), '/firmware/qcom/' + fn)
    for vpath, edits in BINARY_PATCHES:
        data = debugfs_read(img, vpath)
        for old_b, new_b in edits:
            assert len(old_b) == len(new_b) and data.count(old_b) == 1, (vpath, old_b)
            data = data.replace(old_b, new_b)
        ctx = debugfs_label(img, vpath)
        p = os.path.join(work, os.path.basename(vpath))
        open(p, 'wb').write(data)
        cmds.append(f'rm {vpath}')
        cmds.append(f'write {p} {vpath}')
        mode = 0o100755 if '/bin/' in vpath else 0o100644
        cmds.append(f'set_inode_field {vpath} mode 0{mode:o}')
        cmds.append(f'set_inode_field {vpath} uid 0')
        cmds.append(f'set_inode_field {vpath} gid {2000 if "/bin/" in vpath else 0}')
        cmds.append(f'ea_set -f {label_file(ctx)} {vpath} security.selinux')

    for f in REMOVE:
        cmds.append(f'rm /{f}')

    # 5. SELinux: every domain permissive; drop the emulator's precompiled
    # policy so init compiles plat (GSI) + mapping + this vendor policy
    plat_cil = debugfs_read(GSI, '/system/etc/selinux/plat_sepolicy.cil').decode()
    vcil = debugfs_read(img, '/etc/selinux/vendor_sepolicy.cil').decode()
    doms = permissive_cil(plat_cil, vcil)
    extra = '\n;; nexus5x-android17: bring-up, every domain permissive\n' + \
        ''.join(f'(typepermissive {d})\n' for d in doms)
    put_text(vcil + extra, '/etc/selinux/vendor_sepolicy.cil')
    for f in ('precompiled_sepolicy',
              'precompiled_sepolicy.plat_sepolicy_and_mapping.sha256',
              'precompiled_sepolicy.product_sepolicy_and_mapping.sha256',
              'precompiled_sepolicy.system_ext_sepolicy_and_mapping.sha256'):
        cmds.append(f'rm /etc/selinux/{f}')

    script = os.path.join(work, 'debugfs.cmds')
    open(script, 'w').write('\n'.join(cmds) + '\n')
    r = subprocess.run(['debugfs', '-w', '-f', script, img], capture_output=True, text=True)
    errs = [l for l in r.stderr.splitlines()
            if l.strip() and not l.startswith('debugfs ') and 'File not found by ext2_lookup' not in l
            and 'Ext2 directory already exists' not in l]
    if errs:
        print('\n'.join(errs[:20]), file=sys.stderr)
    fsck(img)
    shutil.rmtree(work)
    print(f'{img}: {len(names)} modules, {len(doms)} permissive domains, {SIZE}')


if __name__ == '__main__':
    main()
