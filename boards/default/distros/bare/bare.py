import os

bare_dir = os.path.dirname(os.path.realpath(__file__))

class Builder:
    def baseConfig(self):
        return {
                'name' : 'baremetal-base',
                'distro' : 'bare',
                'workdir' : bare_dir,
                'builder' : self
                }

    # Build a base image in the requested format and return an absolute path to that image
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
