#!/bin/bash
# Enable extended globbing
shopt -s extglob

SUITE_PASS=true

# Reset the test log
echo "" > test.log

echo "Running launch timeout test (should timeout):" | tee -a test.log
echo "This test will reset your terminal"
./sw_manager.py test test/timeout-run.json | grep "timeout while running"
res=$?
reset
echo "Ran launch timeout test (screen was reset)"
if [ $res != 0 ]; then
  echo "Failure" | tee -a test.log
  SUITE_PASS=false
else
  echo "Success" | tee -a test.log
fi

echo "Running build timeout test (should timeout):" | tee -a test.log
./sw_manager.py test test/timeout-build.json | grep "timeout while building"
if [ $? != 0 ]; then
  echo "Failure" | tee -a test.log
  SUITE_PASS=false
else
  echo "Success" | tee -a test.log
fi

# Run the specialized tests (tests that are too complicated for ./sw_manager.py
# test)
echo "Running clean test" | tee -a test.log
./test/clean/test.py >> test.log 
if [ $? != 0 ]; then
  echo "Failure" | tee -a test.log
  SUITE_PASS=false
fi

echo "Running incremental test" | tee -a test.log
./test/incremental/test.py >> test.log
if [ $? != 0 ]; then
  echo "Failure" | tee -a test.log
  SUITE_PASS=false
  exit 1
fi

# Run the bulk tests (all work with the 'test' command)
# Note the funny extended globbing, these are just lists of tests that
# shouldn't be tested (e.g. we exclude the base configs and some specialized
# tests)
echo "Running regular tests" | tee -a test.log
BULK_EXCLUDE="(br-base|fedora-base|incremental|clean|timeout-build|timeout-run)"
./sw_manager.py clean test/!$BULK_EXCLUDE.json | tee -a test.log
./sw_manager.py test test/!$BULK_EXCLUDE.json | tee -a test.log
if [ $? != 0 ]; then
  echo "Failure" | tee -a test.log
  SUITE_PASS=false
else
  echo "Success" | tee -a test.log
fi

# Run the initramfs versions on spike, initramfs runs have many restrictions,
# we only run a few tests here to test basic capabilities
echo "Running initramfs capable tests on spike" | tee -a test.log
IS_INCLUDE="@(command|flist|host-init|jobs|linux-src|overlay|post-run-hook|run|spike|smoke0|spike)"
# ls test/$IS_INCLUDE.json
# exit 0
./sw_manager.py -i clean test/$IS_INCLUDE.json | tee -a test.log
./sw_manager.py -i test -s test/$IS_INCLUDE.json | tee -a test.log
if [ $? != 0 ]; then
  echo "Failure" | tee -a test.log
  SUITE_PASS=false
else
  echo "Success" | tee -a test.log
fi

if [ $SUITE_PASS = false ]; then
  echo "Some tests failed" | tee -a test.log
  exit 1
else
  echo "Full Test Success" | tee -a test.log
  exit 0
fi
