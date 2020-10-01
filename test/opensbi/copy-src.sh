#!/bin/bash
set -e

if [ ! -d linux ]; then
  rsync --exclude ".git" -r ../../boards/firechip/firmware/opensbi .
  patch -p0 < test.patch
fi
