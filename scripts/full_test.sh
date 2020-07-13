#!/bin/bash
# Enable extended globbing
shopt -s extglob

TEST_DIR=../test
MARSHAL_BIN=../marshal

SUITE_PASS=true
mkdir -p test_logs
LOGNAME=$(realpath $(mktemp test_logs/results_full_test.XXXX))

echo "Running Full Test. Results available in $LOGNAME"

# These tests need to run on spike, but not with the no-disk option
echo "Running bare-metal tests" | tee -a $LOGNAME
IS_INCLUDE="@(bare|dummy-bare|spike|spike-jobs|spike-args|rocc)"
$MARSHAL_BIN clean $TEST_DIR/$IS_INCLUDE.json | tee -a $LOGNAME
$MARSHAL_BIN test -s $TEST_DIR/$IS_INCLUDE.json | tee -a $LOGNAME
if [ ${PIPESTATUS[0]} != 0 ]; then
  echo "Failure" | tee -a $LOGNAME
  SUITE_PASS=false
else
  echo "Success" | tee -a $LOGNAME
fi
echo ""

echo "Initializing submodules for linux-based tests" | tee -a $LOGNAME
./init-submodules.sh | tee -a $LOGNAME
echo ""

# We pre-build to avoid potential timeouts on a fresh clone
echo "Pre-building base workloads" | tee -a $LOGNAME
$MARSHAL_BIN build $TEST_DIR/br-base.json
$MARSHAL_BIN build $TEST_DIR/fedora-base.json
echo ""

echo "Running launch timeout test (should timeout):" | tee -a $LOGNAME
echo "This test will reset your terminal"
$MARSHAL_BIN test $TEST_DIR/timeout-run.json | grep "timeout while running"
res=${PIPESTATUS[1]}
reset
echo "Ran launch timeout test (screen was reset)"
if [ $res != 0 ]; then
  echo "Failure" | tee -a $LOGNAME
  SUITE_PASS=false
else
  echo "Success" | tee -a $LOGNAME
fi
echo ""

echo "Running build timeout test (should timeout):" | tee -a $LOGNAME
$MARSHAL_BIN test $TEST_DIR/timeout-build.json | grep "timeout while building"
if [ ${PIPESTATUS[1]} != 0 ]; then
  echo "Failure" | tee -a $LOGNAME
  SUITE_PASS=false
else
  echo "Success" | tee -a $LOGNAME
fi
echo ""

# Run the bulk tests (all work with the 'test' command)
# Note the funny extended globbing, these are just lists of tests that
# shouldn't be tested (e.g. we exclude the base configs and some specialized
# tests)
echo "Running regular tests" | tee -a $LOGNAME
BULK_EXCLUDE="(incremental|clean|timeout-build|timeout-run|bare|dummy-bare|spike-jobs|spike|spike-args|rocc|fsSize|bbl)"
$MARSHAL_BIN clean $TEST_DIR/!$BULK_EXCLUDE.json | tee -a $LOGNAME
$MARSHAL_BIN test $TEST_DIR/!$BULK_EXCLUDE.json | tee -a $LOGNAME
if [ ${PIPESTATUS[0]} != 0 ]; then
  echo "Failure" | tee -a $LOGNAME
  SUITE_PASS=false
else
  echo "Success" | tee -a $LOGNAME
fi
echo ""

# Run the no-disk versions on spike, no-disk runs have many restrictions,
# we only run a few tests here to test basic capabilities
echo "Running no-disk capable tests on spike" | tee -a $LOGNAME
IS_INCLUDE="@(command|flist|host-init|jobs|linux-src|overlay|post-run-hook|run|smoke0|simArgs|bbl)"
$MARSHAL_BIN -d clean $TEST_DIR/$IS_INCLUDE.json | tee -a $LOGNAME
$MARSHAL_BIN -d test -s $TEST_DIR/$IS_INCLUDE.json | tee -a $LOGNAME
if [ ${PIPESTATUS[0]} != 0 ]; then
  echo "Failure" | tee -a $LOGNAME
  SUITE_PASS=false
else
  echo "Success" | tee -a $LOGNAME
fi
echo ""

# Run the specialized tests (tests that are too complicated for ./marshal
# test)
echo "Running clean test" | tee -a $LOGNAME
./$TEST_DIR/clean/test.py >> $LOGNAME 
if [ ${PIPESTATUS[0]} != 0 ]; then
  echo "Failure" | tee -a $LOGNAME
  SUITE_PASS=false
fi
echo ""

echo "Running incremental test" | tee -a $LOGNAME
./$TEST_DIR/incremental/test.py >> $LOGNAME
if [ ${PIPESTATUS[0]} != 0 ]; then
  echo "Failure" | tee -a $LOGNAME
  SUITE_PASS=false
  exit 1
fi
echo ""

echo "Running inheritance test" | tee -a $LOGNAME
./$TEST_DIR/inherit/test.py >> $LOGNAME
if [ ${PIPESTATUS[0]} != 0 ]; then
  echo "Failure" | tee -a $LOGNAME
  SUITE_PASS=false
  exit 1
fi
echo ""

# Ensures that marshal can be called from different PWDs
echo "Running different PWD test" | tee -a $LOGNAME
pushd $TEST_DIR/sameWorkdir
../../marshal test sameDir.json | tee -a $LOGNAME
if [ ${PIPESTATUS[0]} != 0 ]; then
  echo "Failure" | tee -a $LOGNAME
  SUITE_PASS=false
  exit 1
fi
popd
echo ""

echo "Running fsSize test" | tee -a $LOGNAME
pushd $TEST_DIR/fsSize
./test.sh | tee -a $LOGNAME
if [ ${PIPESTATUS[0]} != 0 ]; then
  echo "Failure" | tee -a $LOGNAME
  SUITE_PASS=false
  exit 1
fi
popd
echo ""

echo "Running recursive make test" | tee -a $LOGNAME
pushd $TEST_DIR/makefile
make
if [ ${PIPESTATUS[0]} != 0 ]; then
  echo "Failure" | tee -a $LOGNAME
  SUITE_PASS=false
  exit 1
fi
popd
echo ""

echo "Running workdir test" | tee -a $LOGNAME
pushd $TEST_DIR/testWorkdir
./test.py | tee -a $LOGNAME
if [ ${PIPESTATUS[0]} != 0 ]; then
  echo "Failure" | tee -a $LOGNAME
  SUITE_PASS=false
  exit 1
fi
popd
echo ""

echo "Running workload-paths test" | tee -a $LOGNAME
pushd $TEST_DIR/workload-dirs/
./test.sh | tee -a $LOGNAME
if [ ${PIPESTATUS[0]} != 0 ]; then
  echo "Failure" | tee -a $LOGNAME
  SUITE_PASS=false
  exit 1
fi
popd
echo ""

echo -e "\n\nMarshal full test complete. Log at: $LOGNAME"
if [ $SUITE_PASS = false ]; then
  echo "FAILURE: Some tests failed" | tee -a $LOGNAME
  exit 1
else
  echo "SUCCESS: Full test success" | tee -a $LOGNAME
  exit 0
fi
