#!/bin/bash
# This workload changed a few options in the buildroot config, we verify that
# the changes took in this script.

echo "Starting Test"

#==============================================================================
# Password Test
# This modifies something simple (the password) but doesn't change which files
# are in the overlay.
#==============================================================================
echo "Checking if the password changed"

# This will print an error if testUser already exists but it doesn't break the
# test.
adduser -D testUser

su testUser -c "echo marshalTestPwd | su root -c true"
test_pass=$?

if [ $test_pass -eq 0 ]; then
    echo "PASS"
else
    echo "FAIL"
fi

#==============================================================================
# Vim Test
# The config also removed the vim package. Subtractive changes like this aren't
# handled well by buildroot so we have to be careful in marshal to ensure they
# actually show up (clean when needed).
#==============================================================================
echo "Checking if vim was removed"

which vim
vim_detected=$?
echo "vim detected $vim_detected"

if [ $vim_detected -eq 0 ]; then
    echo "FAIL"
else
    echo "PASS"
fi

poweroff
