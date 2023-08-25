Initial Setup/Installation
==============================

Background/Terminology
--------------------------

.. |manager_machine| replace:: **Manager Machine**
.. |build_farm_machine| replace:: **Build Farm Machines**
.. |run_farm_machine| replace:: **Run Farm Machines**

.. |mach_or_inst| replace:: Machine
.. |mach_or_inst_l| replace:: machines
.. |mach_details| replace:: your local desktop or server
.. |mach_or_inst2| replace:: local machines
.. |simple_setup| replace:: In the simplest setup, a single host machine (e.g. your desktop) can serve the function of all three of these: as the manager machine, the build farm machine (assuming Vivado is installed), and the run farm machine (assuming an FPGA is attached).

.. include:: ../../Terminology-Template.rst


System Setup
----------------------------------

The below sections outline what you need to install to run FireSim on each
machine type in a FireSim cluster. Note that the below three machine types
can all map to a single machine in your setup; in this case, you should follow
all the installation instructions on your single machine, without duplication
(i.e., don't re-run a step on the same machine if it is required on multiple
machine types).

.. warning::
    **We highly recommend using Ubuntu 20.04 LTS as the host operating system for
    all machine types in an on-premises setup, as this is the OS recommended by
    Xilinx.**


1. Fix default ``.bashrc``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

**Machines:** Manager Machine, Run Farm Machines, Build Farm Machines.

We need various parts of the ``~/.bashrc`` file to execute even in non-interactive mode.
To do so, edit your ``~/.bashrc`` file so that the following section is removed:

.. code-block:: bash

   # If not running interactively, don't do anything
   case $- in
        *i*) ;;
          *) return;;
   esac


2. Enable password-less sudo
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

**Machines:** Manager Machine and Run Farm Machines.

Enable passwordless sudo by running ``sudo visudo``, then adding
the following line at the end of the file, replacing ``YOUR_USERNAME_HERE``
with your actual username on the machine:

.. code-block:: bash

   YOUR_USERNAME_HERE ALL=(ALL) NOPASSWD:ALL


Once you have done so, reboot the machines
and confirm that you are able to run ``sudo true`` without being
prompted for a password.


3. Install Vivado Lab and Cable Drivers
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

**Machines:** Run Farm Machines.

Go to the `Xilinx Downloads Website <https://www.xilinx.com/support/download.html>`_ and download ``Vivado 2023.1: Lab Edition - Linux``.

Extract the downloaded ``.tar.gz`` file, then:

.. code-block:: bash

   cd [EXTRACTED VIVADO LAB DIRECTORY]
   sudo ./installLibs.sh
   sudo ./xsetup --batch Install --agree XilinxEULA,3rdPartyEULA --edition "Vivado Lab Edition (Standalone)"

This will have installed Vivado Lab to ``/tools/Xilinx/Vivado_Lab/2023.1/``.

For ease of use, add the following to the end of your ``~/.bashrc``:

.. code-block:: bash

   source /tools/Xilinx/Vivado_Lab/2023.1/settings64.sh


Then, open a new terminal or source your ``~/.bashrc``.

Next, install the cable drivers like so:

.. code-block:: bash

   cd /tools/Xilinx/Vivado_Lab/2023.1/data/xicom/cable_drivers/lin64/install_script/install_drivers/
   sudo ./install_drivers


4. Install the Xilinx XDMA and XVSEC drivers
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

**Machines:** Run Farm Machines.

First, run the following to clone the XDMA kernel module source:

.. code-block:: bash

   cd ~/   # or any directory you would like to work from
   git clone https://github.com/Xilinx/dma_ip_drivers
   cd dma_ip_drivers
   git checkout 0e8d321
   cd XDMA/linux-kernel/xdma

|nitefury_patch_xdma|

.. code-block:: bash

   sudo make install


Now, test that the module can be inserted:

.. code-block:: bash

   sudo insmod /lib/modules/$(uname -r)/extra/xdma.ko poll_mode=1
   lsmod | grep -i xdma


The second command above should have produced output indicating that the XDMA
driver is loaded.

Next, we will do the same for the XVSEC driver, which is pulled from a separate
repository due to kernel version incompatibility:

.. code-block:: bash

   cd ~/   # or any directory you would like to work from
   git clone https://github.com/paulmnt/dma_ip_drivers dma_ip_drivers_xvsec
   cd dma_ip_drivers_xvsec
   git checkout 302856a
   cd XVSEC/linux-kernel/

   make clean all
   sudo make install

Now, test that the module can be inserted:

.. code-block:: bash

   sudo modprobe xvsec
   lsmod | grep -i xvsec


The second command above should have produced output indicating that the XVSEC
driver is loaded.

Also, make sure you get output for the following (usually, ``/usr/local/sbin/xvsecctl``):

.. code-block:: bash

   which xvsecctl


5. Install your FPGA(s)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

**Machines:** Run Farm Machines.

Now, let's attach your |fpga_name|_ FPGA(s) to your Run Farm Machines:

1. Poweroff your machine.

2. Insert your |fpga_name|_ FPGA |fpga_attach_prereq|

3. Attach any additional power cables between the FPGA and the host machine. |fpga_power_info|

4. Attach the USB cable between the FPGA and the host machine for |jtag_help|

5. Boot the machine.

6. Obtain an existing bitstream tar file for your FPGA by opening the ``bitstream_tar`` URL listed
   under |hwdb_entry_name| in the following file: :gh-file-ref:`deploy/sample-backup-configs/sample_config_hwdb.yaml`.
7. Extract the ``.tar.gz`` file to a known location. |mcs_info|

8. Open Vivado Lab and click "Open Hardware Manager". Then click "Open Target" and "Auto connect".

9. Right-click on your FPGA and click "Add Configuration Memory Device". For a |fpga_name|_, choose |fpga_spi_part_number|
   as the Configuration Memory Part. Click "OK" when prompted to program the configuration memory device.

10. For Configuration file, choose the ``firesim.mcs`` |extra_mcs|

11. Uncheck "Verify" and click OK.

12. Right-click on your FPGA and click "Boot from Configuration Memory Device".

13. When programming the configuration memory device is completed, power off your machine fully (i.e., the FPGA should completely lose |dip_switch_extra|)

14. Cold-boot the machine. A cold boot is required for the FPGA to be successfully re-programmed from its flash.

15. Once the machine has booted, run the following to ensure that your FPGA is set up properly:

.. code-block:: bash

   lspci -vvv -d 10ee:903f

If successful, this should show an entry with Xilinx as the manufacturer and
two memory regions. There should be one entry
for each FPGA you've added to the Run Farm Machine.

.. note:: |jtag_cable_reminder|


6. Install sshd
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

**Machines:** Manager Machine, Run Farm Machines, and Build Farm Machines

On Ubuntu, install ``openssh-server`` like so:

.. code-block:: bash

   sudo apt install openssh-server


7. Set up SSH Keys
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

**Machines:** Manager Machine.

On the manager machine, generate a keypair that you will use to ssh from the
manager machine into the manager machine (ssh localhost), run farm machines,
and build farm machines:

.. code-block:: bash

   cd ~/.ssh
   ssh-keygen -t ed25519 -C "firesim.pem" -f firesim.pem
   [create passphrase]

Next, add this key to the ``authorized_keys`` file on the manager machine:

.. code-block:: bash

   cd ~/.ssh
   cat firesim.pem.pub >> authorized_keys
   chmod 0600 authorized_keys

You should also copy this public key into the ``~/.ssh/authorized_keys`` files
on all of your Run Farm and Build Farm Machines.

Returning to the Manager Machine, let's set up an ``ssh-agent``:

.. code-block:: bash

   cd ~/.ssh
   ssh-agent -s > AGENT_VARS
   source AGENT_VARS
   ssh-add firesim.pem


If you reboot your machine (or otherwise kill the ``ssh-agent``), you
will need to re-run the above four commands before using FireSim.
If you open a new terminal (and ``ssh-agent`` is already running),
you can simply run ``source ~/.ssh/AGENT_VARS``.

Finally, confirm that you can now ``ssh localhost`` and ssh into your Run Farm
and Build Farm Machines without being prompted for a passphrase.

8. Install Guestmount
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

**Machines:** Manager Machine and Run Farm Machines

Next, install the ``guestmount`` program:

.. code-block:: bash

   sudo chmod +r /boot/vmlinuz-*
   sudo apt install libguestfs-tools
   sudo chmod +r /boot/vmlinuz-*


This is needed by a variety of FireSim steps that mount disk images in order to copy in/out results of simulations out of the images.
Using ``guestmount`` instead of the standard mount commands allows for users to perform these operations without requiring ``sudo`` (after this initial installation).

Let's double check that ``guestmount`` is functioning correctly on your system. To do so, we'll generate a dummy filesystem image:

.. code-block:: bash

   cd ~/   # or any scratch area
   mkdir sysroot-testing
   cd sysroot-testing
   mkdir sysroot
   dd if=/dev/urandom of=sysroot/myfile bs=1024 count=1024
   virt-make-fs --format=qcow2 --type=ext2 sysroot sysroot.qcow2

Ensure that this command completed without producing an error and that the output file ``sysroot.qcow2`` exists.

Assuming all of this completed successfully (i.e., no error from ``virt-make-fs``), you can delete the ``sysroot-testing`` directory,
since we will not need it any longer.


.. warning:: Due to prior issues we've seen with ``guestmount``, ensure that your FireSim repository
   does not reside on an NFS mount.


9. Check Hard File Limit
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

**Machine:** Manager Machine

Check the output of the following command:

.. code-block:: bash

   ulimit -Hn

If the result is greater than or equal to 16384, you can continue on to "Setting up the FireSim Repo". Otherwise, run:

.. code-block:: bash

   echo "* hard nofile 16384" | sudo tee --append /etc/security/limits.conf

Then, reboot your machine.


10. Verify Run Farm Machine environment
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

**Machines:** Manager Machine and Run Farm Machines

Finally, let's ensure that the |tool_type_lab| tools are properly sourced in
your shell setup (i.e. ``.bashrc``) so that any shell on your Run Farm Machines
can use the corresponding programs.  The environment variables should be
visible to any non-interactive shells that are spawned.

You can check this by running the following on the Manager Machine,
replacing ``RUN_FARM_IP`` with ``localhost`` if your Run Farm machine
and Manager machine are the same machine, or replacing it with the Run Farm
machine's IP address if they are different machines.

.. code-block:: bash

    ssh RUN_FARM_IP printenv


Ensure that the output of the command shows that the |tool_type_lab| tools are
present in the printed environment variables (i.e., ``PATH``).

If you have multiple Run Farm machines, you should repeat this process for
each Run Farm machine, replacing ``RUN_FARM_IP`` with a different Run Farm Machine's
IP address.

Congratulations! We've now set up your machine/cluster to run simulations. Click Next to continue with the guide.
