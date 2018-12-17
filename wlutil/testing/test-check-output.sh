#!/bin/bash
# Who tests the testers? I do.

./check_output.py testAllBadUart testRef/ >> /dev/null
if [ $? == 0 ]; then
  echo "testAllBadUart should have failed, but it passed"
  exit 1
fi

./check_output.py testBadFile testRef/ >> /dev/null
if [ $? == 0 ]; then
  echo "testBadFile should have failed, but it passed"
  exit 1
fi

./check_output.py testBadUart testRef/ >> /dev/null
if [ $? == 0 ]; then
  echo "testBadUart should have failed, but it passed"
  exit 1
fi

./check_output.py testMissingJob testRef/ >> /dev/null
if [ $? == 0 ]; then
  echo "testMissingJob should have failed, but it passed"
  exit 1
fi

./check_output.py testGood testRef/ >> /dev/null
if [ $? != 0 ]; then
  echo "testGood failed"
  exit 1
fi

echo "Success"
