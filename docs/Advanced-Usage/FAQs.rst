FireSim Asked Questions
=============================

I just bumped the FireSim repository to a newer commit and simulations aren't running. What is going on?
--------------------------------------------------------------------------------------------------------

Anytime there is an AGFI bump, FireSim simulations will break/hang due to outdated AFGI.
To get the new default AGFI's you must run the manager initialization again by doing the following:

::

    cd firesim
    source sourceme-f1-manager.sh
    firesim managerinit

Is there a good way to keep track of what AGFI corresponds to what FireSim commit?
----------------------------------------------------------------------------------

When building an AGFI during ``firesim buildbitstream``, FireSim keeps track of what FireSim repository commit was used to build the AGFI.
To view a list of AGFI's that you have built and what you have access to, you can run the following command:

::

    cd firesim
    source sourceme-f1-manager.sh
    aws ec2 describe-fpga-images --fpga-image-ids # List all AGFI images

You can also view a specific AGFI image by giving the AGFI ID (found in ``deploy/config_hwdb.ini``) through the following command:

::

    cd firesim
    source sourceme-f1-manager.sh
    aws ec2 describe-fpga-images --filter Name=fpga-image-global-id,Values=agfi-<Your ID Here> # List particular AGFI image

After querying an AGFI, you can find the commit hash of the FireSim repository used to build the AGFI within the "Description"
field.

For more information, you can reference the AWS documentation at https://docs.aws.amazon.com/cli/latest/reference/ec2/describe-fpga-images.html.

Help, My Simulation Hangs!
----------------------------

Oof. It can be difficult to pin this one down, read through
:ref:`debugging-hanging-simulators` for some tips to get you started.

Should My Simulator Produce Different Results Across Runs?
----------------------------------------------------------

No.

Unless you've intentionally introduced a side-channel (e.g., you're running an
interactive simulation, or you've connected the NIC to the internet), this is
likely a bug in one of your custom bridge implementations or in FireSim. In
fact, for a given target-design, enabling printf synthesis, assertion synthesis,
autocounter, or Auto ILA, should not change the simulated behavior of the machine.

Is there a way to compress workload results when copying back to the manager instance?
--------------------------------------------------------------------------------------

FireSim doesn't support compressing workload results before copying them back to the manager instance.
Instead we recommend that you use a modern filesystem (like ZFS) to provide compression for you.
For example, if you want to use ZFS to transparently compress data:

1. Attach a new volume to your EC2 instance (either at runtime or during launch).
   This is where data will be stored in a compressed format.
2. Make sure that the volume is attached (using something like ``lsblk -f``).
   This new volume should not have a filesystem type and should be unmounted (volume name example: ``nvme1n1``).
3. Install ZFS according to the `ZFS documentation <https://openzfs.github.io/openzfs-docs/Getting%20Started/RHEL-based%20distro/index.html>`__.
   Check ``/etc/redhat-release`` to verify the CentOS version of the manager instance.
4. Mount the volume and setup the ZFS filesystem with compression.

.. warning::
    Creating the zpool will destroy all pre-existing data on that partition.
    Double-check that the device node is correct before running any commands.

::

    # replace /dev/nvme1n1 with the proper device node
    zpool create -o ashift=12 -O compression=on <POOL_NAME> /dev/nvme1n1
    zpool list
    zfs list

5. At this point, you can use ``/<POOL_NAME>`` as a normal directory to store data into where it will
   be compressed. To see the compression ratio, use ``zfs get compressratio``.
