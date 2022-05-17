#! /usr/bin/env bash

# This script is used by VSCode only to launch sbt and run bloopInstall. It
# performs no other tasks (compile, run, test, etc..), so I've omitted many
# of the other command line flags we pass through the make system.

# If we need these to agree we should consider moving stuff into .jvmopts and
# .sbtopts and out of the Makefiles

set -ex
source ../env.sh
sbt "$@"

