import os
import subprocess as sp
import shutil
import logging
import wlutil
import string

# Note: All argument paths are expected to be absolute paths

# Some common directories for this module (all absolute paths)
br_dir = os.path.dirname(os.path.realpath(__file__))
overlay = os.path.join(br_dir, 'firesim-overlay')

initTemplate = string.Template("""#!/bin/sh

SYSLOGD_ARGS=-n
KLOGD_ARGS=-n

start() {
    echo "FIRESIM RUN START" && /firesim.sh $args && echo "FIRESIM RUN END"
}

case "$$1" in
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
  echo "Usage: $$0 {start|stop|restart}"
  exit 1
esac

exit""")

class Builder:

    def baseConfig(self):
        return {
                'name' : 'buildroot-base',
                'distro' : 'br',
                'workdir' : br_dir,
                'builder' : self,
                'img' : os.path.join(br_dir, "buildroot/output/images/rootfs.ext2")
                }

    # Build a base image in the requested format and return an absolute path to that image
    def buildBaseImage(self):
        log = logging.getLogger()
        rootfs_target = "rootfs.img"
        shutil.copy(os.path.join(br_dir, 'buildroot-config'),
                    os.path.join(br_dir, "buildroot/.config"))
        # log.debug(sp.check_output(['make'], cwd=os.path.join(br_dir, "buildroot")))

        # Buildroot complains about some common PERL configurations
        env = os.environ.copy()
        env.pop('PERL_MM_OPT', None)
        wlutil.run(['make'], cwd=os.path.join(br_dir, "buildroot"), env=env)

    # Return True if the base image is up to date, or False if it needs to be
    # rebuilt.
    def upToDate(self):
        # XXX There's something wrong with buildroots makefile, it throws an
        # error and never reports being up to date.
        # XXX DONT COMMIT THIS CHANGE YOUR DEFNITELY GOING TO FORGET TO UNDO THIS
        if os.path.exists(os.path.join(br_dir, "buildroot/output/images/rootfs.ext2")):
            return True
        else: 
            return False
        # makeStatus = sp.call('make -q', shell=True, stdout=sp.DEVNULL, stderr=sp.DEVNULL, cwd=os.path.join(br_dir, 'buildroot'))
        # cfgDiff = sp.call(['diff', '-q', 'buildroot-config', 'buildroot/.config'], stdout=sp.DEVNULL, stderr=sp.DEVNULL, cwd=br_dir)
        # if makeStatus == 0 and cfgDiff == 0:
        #     return True
        # else:
        #     return False

    # Set up the image such that, when run in qemu, it will run the script "script"
    # If None is passed for script, any existing bootscript will be deleted
    @staticmethod
    def generateBootScriptOverlay(script, args):
        # How this works:
        # The buildroot repo has a pre-built overlay with a custom S99run
        # script that init will run last. This script will run the /firesim.sh
        # script at boot. We just overwrite this script.
        scriptDst = os.path.join(overlay, 'firesim.sh')
        if script != None:
            wlutil.run(['cp', script, scriptDst])
        else:
            wlutil.run(['rm', scriptDst])
            # Create a blank init script because overlays won't let us delete stuff
            # Alternatively: we could consider replacing the default.target
            # symlink to disable the firesim target entirely
            wlutil.run(['touch', scriptDst])
        
        wlutil.run(['chmod', '+x', scriptDst])

        with open(os.path.join(overlay, 'etc/init.d/S99run'), 'w') as f:
            if args == None:
                f.write(initTemplate.substitute(args=''))
            else:
                f.write(initTemplate.substitute(args=args))
        
        return overlay
