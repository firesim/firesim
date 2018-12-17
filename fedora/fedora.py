import os
import subprocess as sp
import shutil
import wlutil

# Some common directories for this module (all absolute paths)
fed_dir=os.path.dirname(os.path.realpath(__file__))

# Temporary overlay used for applying init scripts
overlay=os.path.join(fed_dir, 'overlay')

class Builder:
    def baseConfig(self):
        return {
                'name' : 'fedora-base',
                'workdir' : fed_dir,
                'distro' : 'fedora',
                'builder' : self,
                'img' : os.path.join(fed_dir, "rootfs.img")
                }

    def buildBaseImage(self):
        wlutil.run(['make', "rootfs.img"], cwd=fed_dir)

    # Return True if the base image is up to date, or False if it needs to be
    # rebuilt.
    def upToDate(self):
        retcode = sp.call('make -q rootfs.img', shell=True, cwd=fed_dir)
        if retcode == 0:
            return True
        else:
            return False

    def generateBootScriptOverlay(self, script):
        # How this works:
        # The fedora repo has a pre-built overlay with all the systemd paths
        # filled in and a custom boot target (firesim.target) that loads a
        # custom service (firesim.service) that runs a script (/init.sh). We
        # can change the default boot behavior by changing this script.
        scriptDst = os.path.join(overlay, 'firesim.sh')
        if script != None:
            wlutil.run(['cp', script, scriptDst])
        else:
            wlutil.run(['rm', scriptDst])
            # Create a blank init script because overlays won't let us delete stuff
            # Alternatively: we could consider replacing the default.target
            # symlink to disable the firesim target entirely
            wlutil.run(['touch', scriptDst])
        
        # run(['sudo', 'chown', 'root:root', scriptDst])
        wlutil.run(['chmod', '+x', scriptDst])
        return overlay
