#!/bin/bash
set -e

if [ ! -d linux ]; then
  rsync --exclude ".git" -r ../../boards/default/linux .
  patch linux/kernel/reboot.c < test.patch
fi
