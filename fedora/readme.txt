This repo supports a risc-v fedora build with support for the pfa. It includes
everything you need except for a fedora image (see below for details on getting
that).

Don't forget to update submodules!

= Fedora Image =
The fedora image is huge (GBs) so it's not included in the repo. You can get a new one from:
http://fedora-riscv.tranquillity.se/koji/tasks?state=closed&view=flat&method=createAppliance&order=-id

or taken from a submodule in the firesim repo. I haven't tested from scratch,
but there is a fedora_bootstrap.sh script in pfa-exp/scripts that could help
initialize a new disk image.

= Building =
You'll need to build a custom kernel and link it to bbl. The build.sh script
should automate this.

= Booting =
Once you have an image and bbl, you can boot in riscv-qemu using ./launch.sh.

= Terminal Config =
For some reason, fedora+qemu gets the tty geometry wrong with wreaks havoc with
things like vim and less. You can change this using the following instructions:

1. go to a working normal (non-qemu) terminal and run “stty -a”, note the values for “rows” and “columns”
2. go to your qemu session and run “stty rows X columns Y”
3. optional: stick that in your bashrc

= Original Instructions =
Here are the instructions that were originally included from the Fedora folks:

This is the current stage4 disk image for the Fedora/RISC-V project:
https://fedoraproject.org/wiki/Architectures/RISC-V

Sources for these files can be found in:
https://fedorapeople.org/groups/risc-v/SRPMS/
https://github.com/rwmjones/fedora-riscv-stage4
https://github.com/rwmjones/fedora-riscv-kernel

Uncompressing and resizing the disk
-----------------------------------

The disk image virtual size is 4G.  If you need more space you must
resize it offline like this:

  unxz --keep stage4-disk.img.xz
  truncate -s 20G stage4-disk.img
  e2fsck -fp stage4-disk.img
  resize2fs stage4-disk.img

If you are writing the disk image to an SD card you could do:

  xzcat stage4-disk.img.xz > /dev/sdX
  e2fsck -fp /dev/sdX
  resize2fs /dev/sdX

QEMU
----

Assuming you don't have real hardware, we recommend using QEMU.
One of the following versions should work:

* Upstream qemu from git + this patch to work around a problem with the FPU:
  http://lists.nongnu.org/archive/html/qemu-devel/2018-03/msg06483.html

* qemu >= 2.12.0 from Fedora Rawhide (earlier versions will not work).
  This includes the FPU workaround.

* The prebuilt qemu packages here:
  https://copr.fedorainfracloud.org/coprs/rjones/riscv/
  This includes the FPU workaround.

This is the suggested command line:

  qemu-system-riscv64 \
    -nographic \
    -machine virt \
    -smp 4 \
    -m 2G \
    -kernel bbl \
    -object rng-random,filename=/dev/urandom,id=rng0 \
    -device virtio-rng-device,rng=rng0 \
    -append "console=ttyS0 ro root=/dev/vda" \
    -device virtio-blk-device,drive=hd0 \
    -drive file=stage4-disk.img,format=raw,id=hd0 \
    -device virtio-net-device,netdev=usernet \
    -netdev user,id=usernet,hostfwd=tcp::10000-:22

SSH access
----------

The QEMU command tries to forward port 10000 on the host to port 22 on
the guest, which should be running the OpenSSH server:

  ssh -p 10000 root@localhost
  root@localhost's password: riscv

Inside the guest
----------------

(1) The root password is: riscv

(2) You may have to run rpmbuild like this:

  rpmbuild --undefine _annotated_build --define "debug_package %{nil}" ...

(3) ‘nano’ and ‘vi’ are available as editors in the base image.
‘emacs-nox’ can be installed using dnf.

(4) We use ‘dnf versionlock’ to lock the version of GCC to a specific
version (to avoid GCC 8 being pulled in right now).  To override the
lock you will need to use ‘dnf versionlock clear’.

Creating a root filesystem / tar file
-------------------------------------

  virt-tar-out -a stage4-disk.img / - > rootfs.tar

Note that when unpacking this you must run tar as root and use
‘tar --acls --selinux --xattrs’ to preserve all attributes.

Contacting us
-------------

IRC channel #fedora-riscv on FreeNode
