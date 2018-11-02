import os
import subprocess as sp
import shutil

# Some common directories for this module (all absolute paths)
fed_dir=os.path.dirname(os.path.realpath(__file__))

class Builder:
    @staticmethod
    def baseImagePath(fmt):
        return os.path.join(fed_dir, "rootfs." + fmt)
        
    def buildBaseImage(self, fmt):
        raise NotImplementedError("Fedora not working yet")

    # Return True if the base image is up to date, or False if it needs to be
    # rebuilt.
    # XXX right now I just lie and say it's up to date
    def upToDate(self):
        return True
