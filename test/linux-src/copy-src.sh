#!/bin/bash
set -e

rsync --exclude ".git" -r ../../boards/default/linux .
# rsync --exclude ".git" -r ./deleteme/linux .
patch linux/kernel/reboot.c < test.patch
