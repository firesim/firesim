import os
import subprocess as sp
import shutil

# Some common directories for this module (all absolute paths)
br_dir=os.path.dirname(os.path.realpath(__file__))
mnt=os.path.join(br_dir, "disk-mount")

class Builder:
  def buildBaseImage(self, fmt):
    raise NotImplementedError("Fedora not working yet")


