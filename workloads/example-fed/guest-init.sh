#!/bin/bash
# set -x

# This is an example of the sort of thing you might want to do in an init script.
# Note that this script will be run exactly once on your image in qemu. 

# Note: you will see a bunch of fedora boot messages and possibly even a login
# prompt while building as this script runs. Don't worry about the login promt,
# your script is running in the background.

# In this case, we will use fedora's package manager to install something (the
# full-featured 'time' command to replace the shell builtin). We also use pip
# to install a python package used by one of the benchmarks. You can also
# download stuff, compile things that don't support cross-compilation, and/or
# configure your system in this script.

# Note that we call poweroff at the end. This is recomended because this script
# will be run automatically during the build process. If you leave it off, the
# build script will wait for you to interact with the booted image and shut
# down before it continues (which might be useful when debugging a workload).

echo "Installing the real time tool (not the shell builtin)"
dnf install -y time

echo "Installing the 'algorithms' python package for the PySort benchmark"
pip install algorithms 

poweroff
