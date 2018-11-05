import os
import subprocess as sp
import shutil

# Note: All argument paths are expected to be absolute paths

# Some common directories for this module (all absolute paths)
br_dir = os.path.dirname(os.path.realpath(__file__))
overlay = os.path.join(br_dir, 'firesim-overlay')

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
        # How this works:
        # The buildroot repo has a pre-built overlay with a custom S99run
        # script that init will run last. This script will run the /firesim.sh
        # script at boot. We just overwrite this script.
        scriptDst = os.path.join(overlay, 'firesim.sh')
        if script != None:
            sp.check_call(['sudo', 'cp', script, scriptDst])
        else:
            sp.check_call(['sudo', 'rm', scriptDst])
            # Create a blank init script because overlays won't let us delete stuff
            # Alternatively: we could consider replacing the default.target
            # symlink to disable the firesim target entirely
            sp.check_call(['sudo', 'touch', scriptDst])
        
        sp.check_call(['sudo', 'chown', 'root:root', scriptDst])
        sp.check_call(['sudo', 'chmod', '+x', scriptDst])
        return overlay
