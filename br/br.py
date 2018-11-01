import os
import subprocess as sp
import shutil

# Note: All argument paths are expected to be absolute paths

# Some common directories for this module (all absolute paths)
br_dir = os.path.dirname(os.path.realpath(__file__))
mnt = os.path.join(br_dir, "disk-mount")

INIT_SCRIPT_NAME = 'etc/init.d/S99run'

init_script_head = """#!/bin/sh
#

SYSLOGD_ARGS=-n
KLOGD_ARGS=-n

start() {
"""

init_script_tail = """
    sync
    poweroff -f
}

case "$1" in
  start)
	start
	;;
  stop)
	#stop
	;;
  restart|reload)
	start
	;;
  *)
	echo "Usage: $0 {start|stop|restart}"
	exit 1
esac

exit
"""
# Generate a script that will run "command" at boot time on the image
# fsBase should be the root directory of the buildroot filesystem to apply this to


def generate_boot_script(command, fsBase):
    init_script_body = init_script_head + "    " + command + init_script_tail

    # Create a temporary script to avoid sudo access issues in the mounted fs
    temp_script = os.path.join(br_dir, "tmp_init")
    with open(temp_script, 'wt') as f:
        f.write(init_script_body)

    final_script = os.path.join(mnt, INIT_SCRIPT_NAME)
    sp.check_call(['sudo', 'cp', temp_script, final_script])
    sp.check_call(['sudo', 'chmod', '755', final_script])
    sp.check_call(["sudo", "chown", "root:root", final_script])


class Builder:
    # Build a base image in the requested format and return an absolute path to that image
    def buildBaseImage(self, fmt):
        rootfs_target = "rootfs." + fmt
        shutil.copy(os.path.join(br_dir, 'buildroot-config'),
                    os.path.join(br_dir, "buildroot/.config"))
        sp.check_call(['make'], cwd=os.path.join(br_dir, "buildroot"))

        if fmt == 'img':
            return os.path.join(br_dir, "buildroot/output/images/rootfs.ext2")
        elif fmt == 'cpio':
            return os.path.join(br_dir, "buildroot/output/images/rootfs.cpio")
        else:
            raise ValueError(
                "Only img and cpio formats are currently supported")

    # Set up the image such that, when run in qemu, it will run the script "script"
    # If None is passed for script, any existing bootscript will be deleted

    def applyBootScript(self, img, script):
        # Make sure we have a mountpoint to mount to
        sp.check_call(['mkdir', '-p', mnt])

        sp.check_call(['sudo', 'mount', '-o', 'loop', img, mnt])
        try:
            if script != None:
                sp.check_call(['sudo', 'cp', script, mnt])
                sp.check_call(['sudo', 'chmod', "+x", os.path.join(mnt, os.path.basename(script))])
                generate_boot_script("/" + os.path.basename(script), mnt)
            else:
                # -f to suppress any errors if it didn't exist
                sp.check_call(['sudo', 'rm', '-f', INIT_SCRIPT_NAME], cwd=mnt)
        finally:
            sp.check_call(['sudo', 'umount', mnt])
