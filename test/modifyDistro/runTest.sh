#!/bin/bash
# The modify distro test changes buildroot's default password from firesim to
# "marshalTestPwd", this test verifies that the the password indeed changed.

echo "Starting Test"

# This will print an error if testUser already exists but it doesn't break the
# test.
adduser -D testUser

su testUser -c "echo marshalTestPwd | su root -c true"
test_pass=$?

if [ $test_pass ]; then
    echo "PASS"
else
    echo "FAIL"
fi

poweroff
