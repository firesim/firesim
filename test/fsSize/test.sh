#!/bin/bash

# Test the too-big part
echo "Testing error handling for too-big files"

# make some huge files
dd if=/dev/zero of=huge0.dat bs=1M count=1024
dd if=/dev/zero of=huge1.dat bs=1M count=1024
../../marshal test ../fsSize.json
MARSHAL_EXIT=$?

# This test uses a lot of disk space, clean up after ourselves
rm huge*.dat
../../marshal clean ../fsSize.json

exit $MARSHAL_EXIT
