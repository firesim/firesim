#!/bin/bash
set -e

WORKDIR=$(dirname "${BASH_SOURCE[0]}")

rm $WORKDIR/generatedFileInput
rm $WORKDIR/overlay/root/generatedOverlayInput
