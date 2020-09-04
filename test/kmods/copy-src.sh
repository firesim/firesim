#!/bin/bash
set -e

if [ ! -d test-icenet ]; then
  rsync --exclude ".git" -r ../../boards/firechip/drivers/icenet-driver/ ./test-icenet
  echo 'MODULE_DESCRIPTION("kmod icenet override test");' >> test-icenet/icenet.c
fi
