This tests the features around customizing the buildroot distro.

# Overview
There are two main features we're testing:
    1. Providing custom Buildroot config fragments
    2. Environment variable handling (several scenarios)

For environment variables, there are a few features we need to check:
    1. Use $WORKLOAD\_NAME\_PATH from the buildroot config (buildroot's make should pick it up)
    2. Provide custom environment variables
    3. Expand $WORKLOAD\_NAME\_PATH in a custom environment variable
    4. Expand arbitrary existing variables in a custom environment variable

See runTest.sh for details of each test.

## Files
    * distroCfg - this is the custom buildroot kfrag
    * busyboxCfg - This is a completely new busybox configuration file
    * busyboxFragment - This is a busybox kfrag
    * test.py - This is the test script from the host
    * runTest.sh - This is the guest test script
