import logging
import git
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

    # Apply linux patches to the default kernel
    patches = list(board_dir.glob("*.patch"))

    linuxRepo = git.Repo(linux_dir)
    if linuxRepo.is_dirty():
        log.warning("Linux source dirty, skipping patches. You should manually check that the following patches have been applied (or are not needed):")
        log.warning([ str(p) for p in patches])
    else:
        log.info("Applying linux patches to default linux source")
        try:
            for patch in patches:
                log.info("Applying: " + str(patch))
                run(['git', 'apply', str(patch)], cwd=linux_dir)
        except:
            log.error("Failed to apply patches. If you've changed the default linux, you should re-evaluate the patches.")

