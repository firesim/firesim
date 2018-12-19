#!/bin/bash
set -e

# Enable extended globbing
shopt -s extglob

echo "Running regular tests"
./sw_manager.py test test/!(br-base|fedora-base).json

echo "Running initramfs capable tests on spike"
./sw_manager.py -i test -s test/!(hard|bare|br-base|fedora-base).json
