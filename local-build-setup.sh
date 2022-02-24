#!/usr/bin/env bash

# wrapper to log all output from the real local-build-setup script, local-build-setup-nolog.sh

set -e
set -o pipefail

bash local-build-setup-nolog.sh $@ 2>&1 | tee local-build-setup-log
