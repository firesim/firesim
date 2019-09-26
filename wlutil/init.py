import logging
from .wlutil import *
from .br import br

# Initialize wlutil for the first time
def oneTimeInit():
    log = logging.getLogger()

    # Setup disk initramfs dirs
    disk_dirs = ["bin", 'dev', 'etc', 'proc', 'root', 'sbin', 'sys', 'usr/bin', 'usr/sbin', 'mnt/root']
    for d in disk_dirs:
        if not (initramfs_dir / 'disk' / d).exists():
            (initramfs_dir / 'disk' / d).mkdir(parents=True)

    # Make busybox (needed for the initramfs)
    log.info("Building busybox (used in initramfs)")
    shutil.copy(wlutil_dir / 'busybox-config', busybox_dir / '.config')
    run(['make', jlevel], cwd=busybox_dir)
    shutil.copy(busybox_dir / 'busybox', initramfs_dir / 'disk' / 'bin/')
