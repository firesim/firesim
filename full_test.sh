#!/bin/bash
# Enable extended globbing
shopt -s extglob

# Reset the test log
echo "" > test.log

# Run the specialized tests (tests that are too complicated for ./sw_manager.py
# test)
echo "Running clean test" | tee -a test.log
./test/clean/test.py >> test.log 
if [ $? != 0 ]; then
  echo "Failure" | tee -a test.log
  exit 1
fi

echo "Running incremental test" | tee -a test.log
./test/incremental/test.py >> test.log
if [ $? != 0 ]; then
  echo "Failure" | tee -a test.log
  exit 1
fi

# Run the bulk tests (all work with the 'test' command)
# Note the funny extended globbing, these are just lists of tests that
# shouldn't be tested (e.g. we exclude the base configs and some specialized
# tests)
echo "Running regular tests" | tee -a test.log
BULK_EXCLUDE="(br-base|fedora-base|incremental|clean)"
./sw_manager.py test test/!$BULK_EXCLUDE.json | tee -a test.log

# Run the initramfs versions on spike, we exclude a few tests that don't make
# sense to use with initramfs and/or spike (e.g. bare-metal)
echo "Running initramfs capable tests on spike" | tee -a test.log
IS_EXCLUDE="(hard|bare|br-base|fedora-base|incremental|clean)"
./sw_manager.py -i test -s test/!$IS_EXCLUDE.json | tee -a test.log

echo "Full Test Success" | tee -a test.log
