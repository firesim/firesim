This tests support for using a custom spike. The test is a simple baremetal
hello-world. Spike is cloned from github and patched to print out "Global :
spike" when it's run. One quirk is that spike must mess around with stdout
somewhere (no idea how they manage this) and "Global : spike" gets printed
twice. This test just rolls with it.

Note: Using a custom spike is only needed if you have some special instructions
or accelerators. Most workloads should not include a 'spike' field in their
config.
