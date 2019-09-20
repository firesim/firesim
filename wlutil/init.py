import logging
from .wlutil import *
from .br import br

# Initialize wlutil for the first time
def oneTimeInit():
    log = logging.getLogger()

    # Build initramfs fs structure (git can't save these because reasons)
    initramfs_dirs = ["bin", 'dev', 'etc', 'proc', 'root', 'sbin', 'sys', 'usr/bin', 'usr/sbin', 'mnt/root']
    for d in initramfs_dirs:
        if not (initramfs_root / d).exists():
            (initramfs_root / d).mkdir(parents=True)

    # Make busybox (needed for the initramfs)
    log.info("Building busybox (used in initramfs)")
    shutil.copy(wlutil_dir / 'busybox-config', busybox_dir / '.config')
    run(['make', jlevel], cwd=busybox_dir)
    shutil.copy(busybox_dir / 'busybox', initramfs_root / 'bin/')
