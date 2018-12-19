#!/bin/bash

# Enable extended globbing
shopt -s extglob

FAIL=0

echo "Running Clean Test"
# This should succeed the first time you run it
./sw_manager.py test test/clean.json
if [ $? != 0 ]; then
  echo "Test Failed"
  $FAIL=1
else
  # This should fail (clean keeps adding to it's output)
  ./sw_manager.py test test/clean.json
  if [ $? == 0 ]; then
    echo "Test Failed"
    $FAIL=1
  else
    # This should work after cleaning
    ./sw_manager.py clean test/clean.json
    ./sw_manager.py test test/clean.json
    if [ $? != 0 ]; then
      echo "Test Failed"
      $FAIL=1
    fi
  fi
fi

echo "Running incremental build test"

echo "Running regular tests"
./sw_manager.py test test/!(br-base|fedora-base|incremental).json
if [ $? != 0 ]; then
  echo "Test Failed"
  $FAIL=1
fi

echo "Running initramfs capable tests on spike"
./sw_manager.py -i test -s test/!(hard|bare|br-base|fedora-base|incremental).json
if [ $? != 0 ]; then
  echo "Test Failed"
  $FAIL=1
fi

if [ FAIL == 1 ]; then
  echo "FULL TEST FAILURE: some tests failed"
  exit 1
else
  echo "FULL TEST SUCCESS: All tests passed"
  exit 0
fi
