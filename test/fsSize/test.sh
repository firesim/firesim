#!/bin/bash

# Test the too-big part
echo "Testing error handling for too-big files"

# make some huge files
echo "Generating input files"
dd if=/dev/zero of=huge0.dat bs=1M count=1024
dd if=/dev/zero of=huge1.dat bs=1M count=1024

echo "Running marshal build"
../../marshal test ../fsSize.json
MARSHAL_EXIT=$?

# This test uses a lot of disk space, clean up after ourselves
echo "Cleaning up"
rm huge*.dat
../../marshal clean ../fsSize.json

if [ $MARSHAL_EXIT != 0 ]; then
  echo "FsSize Test FAILURE"
  return $MARSHAL_EXIT
else
  echo "FsSize Test SUCCESS"
  exit $MARSHAL_EXIT
fi
