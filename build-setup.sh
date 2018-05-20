#!/usr/bin/env bash

# wrapper to log all output from the real build-setup script, build-setup-nolog.sh

set -e
set -o pipefail

bash build-setup-nolog.sh $@ 2>&1 | tee build-setup-log
