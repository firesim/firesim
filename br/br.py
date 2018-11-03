import os
import subprocess as sp
import shutil

# Note: All argument paths are expected to be absolute paths

# Some common directories for this module (all absolute paths)
br_dir = os.path.dirname(os.path.realpath(__file__))
mnt = os.path.join(br_dir, "disk-mount")
overlay = os.path.join(br_dir, 'firesim-overlay')

INIT_SCRIPT_NAME = 'etc/init.d/S99run'

init_script_head = """#!/bin/sh
#

SYSLOGD_ARGS=-n
KLOGD_ARGS=-n

start() {
"""

init_script_tail = """
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

# Generate a script that will run "command" at boot time on the image.
# This script will take the form of an overlay
# Returns a path to the filesystem overlay containing the boot script
def _generate_boot_script(command):
    init_script_body = init_script_head + "    " + command + init_script_tail

    # Create a temporary script to avoid sudo access issues in the overlay
    temp_script = os.path.join(br_dir, "tmp_init")
    with open(temp_script, 'wt') as f:
        f.write(init_script_body)

    final_script = os.path.join(overlay, INIT_SCRIPT_NAME)
    sp.check_call(['sudo', 'mkdir', '-p', os.path.dirname(final_script)])
    sp.check_call(['sudo', 'cp', temp_script, final_script])
    sp.check_call(['sudo', 'chmod', '755', final_script])
    sp.check_call(["sudo", "chown", "-R", "root:root", overlay])

    return overlay

class Builder:
    @staticmethod
    def baseImagePath(fmt):
        if fmt == 'img':
            return os.path.join(br_dir, "buildroot/output/images/rootfs.ext2")
        elif fmt == 'cpio':
            return os.path.join(br_dir, "buildroot/output/images/rootfs.cpio")
        else:
            raise ValueError(
                "Only img and cpio formats are currently supported")

    # Build a base image in the requested format and return an absolute path to that image
    def buildBaseImage(self, fmt):
        rootfs_target = "rootfs." + fmt
        shutil.copy(os.path.join(br_dir, 'buildroot-config'),
                    os.path.join(br_dir, "buildroot/.config"))
        sp.check_call(['make'], cwd=os.path.join(br_dir, "buildroot"))
        return self.baseImagePath(fmt)

    # Return True if the base image is up to date, or False if it needs to be
    # rebuilt.
    # XXX right now I just lie and say it's up to date
    def upToDate(self):
        return True

    # Set up the image such that, when run in qemu, it will run the script "script"
    # If None is passed for script, any existing bootscript will be deleted
    @staticmethod
    def generateBootScriptOverlay(script):
        sp.check_call(['sudo', 'mkdir', '-p', overlay])
        if script != None:
            sp.check_call(['sudo', 'cp', script, overlay])
            sp.check_call(['sudo', 'chmod', "+x", os.path.join(overlay, os.path.basename(script))])
            _generate_boot_script("/" + os.path.basename(script))
        else:
            _generate_boot_script("")
            
        return overlay
