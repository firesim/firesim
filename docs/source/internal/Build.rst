Build Process
=====================
``wlutil/build.py``

The goal of building a workload is to produce a working boot binary and
(optionally) a root filesystem to boot from. The same outputs are used for
Spike, Qemu, and FireSim. The one exception is that Spike does not support a
disk, so users may choose to create an initramfs-only version of their workload
for Spike (that binary will boot on Qemu and FireSim as well). The build process
proceeds as follows:

Build Parents
--------------------
The first step is to make sure the workload's base workload is ready. Marshal
will first follow the dependency chain of bases and ensure that all
dependencies are built before starting on the requested workload. Once the
immediate parent is completed, Marshal begins the build process by create a
copy of the parent's root filesystem to use as the basis for the requested
workload (the distros hard-code their rootfs's to end the recursion).

Host Init
-------------------
Before doing anything else, Marshal runs the workload's ``host-init`` script
(if any) to prepare the workload. This script is allowed to do anything it
wants, so we must run it early in the process in case it changes anything from
the linux kernel source to the root filesystem overlay.

Build Binary
--------------------------------------
``wlutil/build.py:makeBin()``

We build the boot binary before finishing the rootfs because we may need to
boot the workload in Qemu in order to build it. This step is skipped if the
user provided a hard-coded boot binary.

Create Final Linux Configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
Users provide only kernel configuration fragments that must be processed to
create the real linux configuration. We first run 'make ARCH=riscv defconfig'
in the linux source directory (either default or user-provided). We then append
configuration options to include an initramfs (CONFIG_BLK_DEV_INITRD and
CONFIG_INITRAMFS_SOURCE), more on that below. We then call a script provided by
Linux to combine the kernel fragments
(``riscv-linux/scripts/kconfig/merge_config.sh``).

Build Platform Drivers
^^^^^^^^^^^^^^^^^^^^^^^^^
``wlutil/build.py:makeDrivers()``
FireSim provides a number of non-standard devices that require custom linux
drivers. In particular, the block device driver is needed in order to boot a
working system. Instead of maintaining a custom fork of the linux kernel (and
requiring users to keep in sync with it), we provide a custom initramfs that
boots before your main system and loads the drivers.

The drivers for firesim are provided under ``boards/firechip/drivers``. Marshal
first runs ``make modules_prepare`` in the linux source tree, and then compiles
each driver against the provided source. This happens on each new build to
ensure they receive the latest kernel source and configuration (especially
important if the workload provides a custom kernel). We currently do not
support alternative drivers, so any custom linux kernel must be compatible with
the default kernel with regard to these drivers.

Generate Initramfs
^^^^^^^^^^^^^^^^^^^^^^^^^
``wlutil/build.py:makeInitramfs()``

Because some drivers must be loaded in order to boot, we package them into a
custom initramfs that is compiled into the kernel.  Marshal generates this
archive by staging several filesystems at ``wlutil/initramfs{disk, nodisk,
drivers}``:

* ``disk/``: contains a fully-functioning root filesystem with a busybox-based
  environment and an init script that knows to load drivers and look for a disk
  to boot from (either ``/dev/vda`` for qemu or ``/dev/iceblk`` for firesim).
* ``nodisk/``: contains just the init script to load drivers (it must be
  combined with a working root filesystem).
* ``drivers/``: contains the platform drivers built earlier.
* ``devNodes.cpio``: A pre-built archive containing the ``/dev/console`` and
  ``/dev/tty`` special files. These require a special procedure to create so we
  only do it once and commit the result.

Marshal combines the needed initramfs sources in a temporary directory into a
single cpio archive and configures the kernel to include this archive at boot
time.

Note that for nodisk workloads, we additionally include the entire contents of
the workload's rootfs into the initramfs. In this case, the init script in
``wlutil/initramfs/nodisk/init`` simply loads the drivers and calls the
target's ``/sbin/init`` to finish booting.

Linux Kernel Generation and Linking
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
With all of the dependencies finished, we can finally compile the Linux kernel
and link it with the bootloader. While each workload can use a custom kernel
source, all workloads use the same bootloader (for now), located at
``riscv-pk/``. The final linked bbl+linux+initramfs is coppied into
``images/workloadName-bin``.

Build Rootfs
-------------------
``wlutil/build.py:makeImage()``

Add Files
^^^^^^^^^^^^^^^^^
Marshal internally converts both the ``files`` and ``overlay`` options into a
list of ``FileSpec`` objects that describe the source and destination paths. We
then mount the guest rootfs on ``disk-mount/`` using guestmount (see
``applyOverlay()`` and ``copyImageFiles()`` in ``wlutil/wlutil.py``).

.. Note:: Guestmount was used to remove the need for root permissions, but it
  is somewhat slower and doesn't play nice with Ubuntu. The mounting method can
  be changed via the ``mountImg()`` decorator in ``wlutil/wlutil.py``.

Guest Init
^^^^^^^^^^^^^^^
Now that we have a working binary and root filesystem, we can run the user's
``guest-init`` script (if provided). We configure the image to run this script
on boot (see below for how), and boot exacly once in Qemu.

Run Script or Command
^^^^^^^^^^^^^^^^^^^^^^^^
The final step is to apply the user's ``run`` script or ``command`` options (if
any). For simplicity, commands are converted into a run script (stored in
``wlutil/generated/_command.sh``) before proceeding.

Run scripts are handled in a per-distro fashion (since distros acheive it in
different ways). Marshal abstracts this by requesting that the distribution
generate a "bootScriptOverlay" that we apply to the image. In Buildroot, this
places the script in a known location and uses a hard-coded init script that
runs it. Fedora has a systemd service that runs the script.
