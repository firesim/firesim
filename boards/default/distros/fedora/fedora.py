import subprocess as sp
import shutil
import pathlib
import wlutil
import re

serviceTemplate = """[Unit]
Requires=multi-user.target
After=multi-user.target
Before=firesim.target
Wants=firesim.target

[Service]
ExecStart=/etc/firesim/{scriptName} {scriptArgs}
StandardOutput=journal+console"""

# Some common directories for this module (all absolute paths)
fed_dir = pathlib.Path(__file__).resolve().parent

# Temporary overlay used for applying init scripts
overlay = fed_dir / 'overlay'


# Fedora doesn't support any options
def hashOpts(opts):
    return None


# Fedora doesn't support any options
def mergeOpts(base, new):
    return base


def initOpts(cfg):
    return


class Builder:
    def __init__(self, opts):
        return

    def getWorkload(self):
        return {
                'name': 'fedora-base',
                'isDistro': True,
                'distro': {
                    'name': 'fedora',
                    'opts': {}
                },
                'workdir': fed_dir,
                'builder': self,
                'img': fed_dir / "rootfs.img"
                }

    def buildBaseImage(self):
        wlutil.run(['make', "rootfs.img"], cwd=fed_dir)

    def fileDeps(self):
        return []

    # Return True if the base image is up to date, or False if it needs to be
    # rebuilt.
    def upToDate(self):
        def checkMake():
            retcode = sp.call('make -q rootfs.img', shell=True, cwd=fed_dir)
            if retcode == 0:
                return True
            else:
                return False
        return [(checkMake, ())]

    def generateBootScriptOverlay(self, script, args):
        # How this works:
        # The fedora repo has a pre-built overlay with all the systemd paths
        # filled in and a custom boot target (firesim.target) that loads a
        # custom service (firesim.service) that runs a script (/init.sh). We
        # can change the default boot behavior by changing this script.
        scriptDst = overlay / 'etc/firesim/firesim.sh'
        if script is not None:
            print("applying script: " + str(scriptDst))
            shutil.copy(script, scriptDst)
        else:
            scriptDst.unlink()
            # Create a blank init script because overlays won't let us delete stuff
            # Alternatively: we could consider replacing the default.target
            # symlink to disable the firesim target entirely
            scriptDst.touch()

        scriptDst.chmod(0o755)

        # Create the service script
        if args is None:
            serviceScript = serviceTemplate.format(scriptName='firesim.sh', scriptArgs='')
        else:
            serviceScript = serviceTemplate.format(scriptName='firesim.sh', scriptArgs=' '.join(args))

        with open(overlay / 'etc/systemd/system/firesim.service', 'w') as f:
            f.write(serviceScript)

        return overlay

    def stripUart(self, lines):
        stripped = []
        pat = re.compile(r".*firesim.sh\[\d*\]: (.*\n)")
        for line in lines:
            match = pat.match(line)
            if match:
                stripped.append(match.group(1))

        return stripped
