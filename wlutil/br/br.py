import os
import subprocess as sp
import shutil
import logging
import string
import pathlib
import git
import doit
from .. import wlutil

# Note: All argument paths are expected to be absolute paths

# Some common directories for this module (all absolute paths)
br_dir = pathlib.Path(__file__).parent
overlay = br_dir / 'overlay'
br_image = br_dir / 'buildroot' / 'output' / 'images' / 'rootfs.ext2'

initTemplate = string.Template("""#!/bin/sh

SYSLOGD_ARGS=-n
KLOGD_ARGS=-n

start() {
    echo "launching firesim workload run/command" && /firesim.sh $args && echo "firesim workload run/command done"
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

def buildConfig():
    """Construct the final buildroot configuration for this environment. After
    calling this, it is safe to call 'make' in the buildroot directory."""

    toolVer = wlutil.getToolVersions()
    # Contains options specific to the build enviornment (br is touchy about this stuff)
    toolKfrag = wlutil.getOpt('gen-dir') / 'brToolKfrag'
    with open(toolKfrag, 'w') as f:
        f.write("BR2_TOOLCHAIN_EXTERNAL_HEADERS_"+toolVer['linuxMaj']+"_"+toolVer['linuxMin']+"=y\n")
        f.write("BR2_TOOLCHAIN_HEADERS_AT_LEAST_"+toolVer['linuxMaj']+"_"+toolVer['linuxMin']+"=y\n")
        f.write('BR2_TOOLCHAIN_HEADERS_AT_LEAST="'+toolVer['linuxMaj']+"."+toolVer['linuxMin']+'"\n')
        f.write('BR2_TOOLCHAIN_GCC_AT_LEAST_'+toolVer['gcc']+'=y\n')
        f.write('BR2_TOOLCHAIN_GCC_AT_LEAST="'+toolVer['gcc']+'"\n')
        f.write('BR2_TOOLCHAIN_EXTERNAL_GCC_'+toolVer['gcc']+'=y\n')
        f.write('BR2_JLEVEL='+str(os.cpu_count())+'\n')

    # Default Configuration (allows us to bump BR independently of our configs)
    defconfig = wlutil.getOpt('gen-dir') / 'brDefConfig'
    wlutil.run(['make', 'defconfig'], cwd=(br_dir / 'buildroot'))
    shutil.copy(br_dir / 'buildroot' / '.config', defconfig)

    # Our config fragment, specifies differences from the default
    marshalKfrag = br_dir / 'buildroot-config'

    mergeScript = br_dir / 'merge_config.sh'
    wlutil.run([mergeScript, str(defconfig), str(toolKfrag), str(marshalKfrag)],
            cwd=(br_dir / 'buildroot'))
    
def buildBuildRoot():
    wlutil.checkSubmodule(br_dir / 'buildroot')
    buildConfig()

    # Buildroot complains about some common PERL configurations
    env = os.environ.copy()
    env.pop('PERL_MM_OPT', None)
    wlutil.run(['make'], cwd=br_dir / "buildroot", env=env)

class Builder:

    def baseConfig(self):
        return {
                'name' : 'buildroot-base',
                'distro' : 'br',
                'workdir' : br_dir,
                'builder' : self,
                'img' : str(br_image)
                }

    # Build a base image in the requested format and return an absolute path to that image
    def buildBaseImage(self):
        """Ensures that the image file specified by baseConfig() exists and is up to date.

        This is called as a doit task.
        """
        try:
            buildBuildRoot()
        except wlutil.SubmoduleError as e:
            return doit.exceptions.TaskFailed(e)

    def fileDeps(self):
        # List all files that should be checked to determine if BR is uptodate
        deps = []
        deps.append(br_dir / 'buildroot-config')
        deps.append(pathlib.Path(__file__))

        return deps

    # Return True if the base image is up to date, or False if it needs to be
    # rebuilt. This is in addition to the files in fileDeps()
    def upToDate(self):
        return [wlutil.config_changed(wlutil.checkGitStatus(br_dir / 'buildroot'))]

    # Set up the image such that, when run in qemu, it will run the script "script"
    # If None is passed for script, any existing bootscript will be deleted
    @staticmethod
    def generateBootScriptOverlay(script, args):
        # How this works:
        # The buildroot repo has a pre-built overlay with a custom S99run
        # script that init will run last. This script will run the /firesim.sh
        # script at boot. We just overwrite this script.
        scriptDst = overlay / 'firesim.sh'
        if script != None:
            shutil.copy(script, scriptDst)
        else:
            scriptDst.unlink()
            # Create a blank init script because overlays won't let us delete stuff
            # Alternatively: we could consider replacing the default.target
            # symlink to disable the firesim target entirely
            scriptDst.touch()
        
        scriptDst.chmod(0o755)

        with open(overlay / 'etc/init.d/S99run', 'w') as f:
            if args == None:
                f.write(initTemplate.substitute(args=''))
            else:
                f.write(initTemplate.substitute(args=' '.join(args)))
        
        return overlay
