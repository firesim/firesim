import os

bare_dir = os.path.dirname(os.path.realpath(__file__))


def hashOpts(opts):
    return None


def mergeOpts(base, new):
    return base


def initOpts(cfg):
    pass


class Builder:
    """Bare is basically a noop just to have consistency with other distros"""
    def __init__(self, opts):
        pass

    def getWorkload(self):
        return {
                'name': 'bare',
                'isDistro': True,
                'distro': {
                    'name': 'bare',
                    'opts': {}
                },
                'workdir': bare_dir,
                'qemu': None,
                'builder': self
                }

    def buildBaseImage(self):
        raise NotImplementedError("Baremetal workloads currently do not support disk images")

    def upToDate(self):
        """Report whether the distro is up to date or not.

        Trivially true because the bare-distro doesn't actually do anything
        """
        return [True]

    # Set up the image such that, when run in qemu, it will run the script "script"
    # If None is passed for script, any existing bootscript will be deleted
    @staticmethod
    def generateBootScriptOverlay(script):
        raise NotImplementedError("Baremetal code does not currently support 'run', 'init', or 'overlay'")

    def stripUart(self, lines):
        return lines
