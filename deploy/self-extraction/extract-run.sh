#!/usr/bin/env bash

BINNAME=$1

# Decode the arguments as they are base64 encoded
# Encoding is required due to the manager using "$(sed ...)" in the arguments
# We need a way to delay the evaulation of the "$(sed)" until after
# the self-extraction runs. Encoding delays evaluation so we can do it below
DECODED=$(echo $2 | base64 -d)

# Now evaulate the "$(sed)" (and anything else) by passing it to bash
EXPANDED=$(bash -c "echo $DECODED")

# Run the binary with the expanded arguments
$BINNAME $EXPANDED
