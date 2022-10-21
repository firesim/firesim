This tests support for using a custom spike. The test is a simple baremetal
hello-world. Spike is cloned from github and patched to print out "Global :
spike" when it's run. One quirk is that spike must mess around with stdout
somewhere (no idea how they manage this) and "Global : spike" gets printed
twice. This test just rolls with it.

Note: Using a custom spike is only needed if you have some special instructions
or accelerators. Most workloads should not include a 'spike' field in their
config.

= General Strategy for using custom Spike =
See build.sh for the tl;dr

Setting up a non-standard spike alongside the default spike is nontrivial.
Spike compiles into a set of shared libraries and an executable. The executable
includes a hard-coded library search path for the spike libraries (using the
RPATH elf field). It does not respect LD\_LIBRARY\_PATH when loading those
libraries. You *must* run `make` and `make install` for spike to work (running
the binary in build/ directly will silently use the system-libraries instead of
the updated libraries).

The process goes as follows:

cd riscv-isa-sim/
mkdir build
cd build
../configure --with-fesvr=$RISCV --prefix=/local/spike/install/ --with-boost=no --with-boost-asio=no --with-boost-regex=no
make
make install
/local/spike/install/bin/spike specialProgram

You must make /and/ make install for any changes to take effect.
