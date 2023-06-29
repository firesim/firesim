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


System requirements and Setup
----------------------------------

The below sections outline what you need to install to run FireSim on each
machine type in a FireSim cluster. Note that the below three machine types
can all map to a single machine in your setup; in this case, you should follow
all the installation instructions on your single machine, without duplication
(if a step is required on multiple machine types).

**We highly recommend using Ubuntu 20.04 LTS as the host operating system for
all machine types in an on-premises setup, as this is the OS recommended by
Xilinx.**


Fix default .bashrc
^^^^^^^^^^^^^^^^^^^^^^^

Machines: Manager Machine, Run Farm Machines, Build Farm Machines.

Edit your ``.bashrc`` file so that the following section is no longer
present:

.. code-block:: bash

   # IF not running interactively, don't do anything
   case
   ...
   esac


Password-less sudo
^^^^^^^^^^^^^^^^^^^^^^^^^

Machines: Manager Machine and Run Farm Machines.

Enable passwordless sudo by running ``sudo visudo``, then adding
the following line at the end of the file:

.. code-block:: bash

   YOUR_USERNAME_HERE ALL=(ALL) NOPASSWD:ALL


Once you have done so, reboot the Manager Machines and Run Farm Machines
and confirm that you are able to run ``sudo true`` without being
prompted for a password.


Install Vivado Lab and Cable Drivers
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Machines: Run Farm Machines.

Go to the `Xilinx Downloads Website <https://www.xilinx.com/support/download.html>`_ and download "Vivado 2023.1: Lab Edition - Linux".

Extract the downloaded ``.tar.gz`` file, then:

.. code-block:: bash

   cd [EXTRACTED VIVADO LAB DIRECTORY]
   sudo ./installLibs.sh
   sudo ./xsetup --batch Install --agree XilinxEULA,3rdPartyEULA --edition "Vivado Lab Edition (Standalone)"

This will have installed Vivado Lab to ``/tools/Xilinx/Vivado_Lab/2023.1/``.

For ease of use, add the following to the end of your ``.bashrc``:

.. code-block:: bash

   source /tools/Xilinx/Vivado_Lab/2023.1/settings64.sh


Then, open a new terminal or source your ``.bashrc``.

Next, install the cable drivers like so:

.. code-block:: bash

   cd /tools/Xilinx/Vivado_Lab/2023.1/data/xicom/cable_drivers/lin64/install_script/install_drivers/
   sudo ./install_drivers


Install the Xilinx XDMA and XVSEC drivers
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Machines: Run Farm Machines.

Next, run the following:

.. code-block:: bash

   cd ~/   # or any directory you would like to work from
   git clone https://github.com/Xilinx/dma_ip_drivers
   git checkout 0e8d321

   cd XDMA/linux-kernel/xdma
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

   cd ~/
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


Install your FPGA(s)
^^^^^^^^^^^^^^^^^^^^^

Machines: Run Farm Machines.

Now, let's attach your FPGAs to your Run Farm Machines:

1. Poweroff your machine.

2. Insert your FPGA into an open PCIe slot in the machine.

3. Attach any additional power cables between the FPGA and the host machine.

4. Attach the USB cable between the FPGA and the host machine for JTAG.

5. Boot the machine.

6. Download a bitstream tar file for your FPGA using one of the links from this
   file: `FireSim Sample HWDB
   <https://github.com/firesim/firesim/blob/main/deploy/sample-backup-configs/sample_config_hwdb.yaml>`_.
   If there are multiple bitstreams listed for your FPGA, you can choose any
   bitstream.

7. Extract the ``.tar.gz`` file to a known location. Inside, you will find
   three files; the one we are currently interested will be called
   ``firesim.mcs``. Note the full path of this ``firesim.mcs`` file for the
   next step.

8. Open Vivado Lab and click "Open Hardware Manager". Then click "Open Target" and "Auto connect".

9. Right-click on your FPGA board and click "Add configuration device". Choose |fpga_spi_part_number|
   as the part.

10. For configuration file, choose the ``firesim.mcs`` file from step 7.

11. Uncheck "verify" and click OK.

12. When flashing is completed, power off your machine fully.

13. Cold-boot the machine (i.e., the FPGA should have completely lost power). A cold boot is required for the FPGA to be successfully re-programmed from the attached flash.

14. Once the machine has rebooted, run the following to ensure that your FPGA is set up properly:

.. code-block:: bash

   lspci -vvv -d 10ee:903f

If successful, this should show an entry with Xilinx as the manufacturer and two memory regions, one 32M wide and one 64K wide.


Install sshd
^^^^^^^^^^^^^^^

Machines: Manager Machine, Run Farm Machines, and Build Farm Machines

On Ubuntu, install ``openssh-server`` like so:

.. code-block:: bash

   sudo apt install openssh-server


Set up SSH Keys
^^^^^^^^^^^^^^^^^^^^^

Machines: Manager Machine.

On the manager machine, generate a keypair that you will use to ssh from the
manager machine into the manager machine (ssh localhost), run farm machines,
and build farm machines:

.. code-block:: bash

   cd ~/.ssh
   ssh-keygen -t ed25519 -C "firesim" -f firesim.pem
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


If you reboot your machine (or otherwise kill the ``ssh-agent``, you
will need to re-run the above four commands before using FireSim.
If you only open a new terminal (and ``ssh-agent`` is already running),
you can simply re-run ``source ~/.ssh/AGENT_VARS``.

Finally, confirm that you can now ``ssh localhost`` and ssh into your Run Farm
and Build Farm Machines without being prompted for a passphrase.


TODO: Verify your environment
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Machines: Manager Machine, Run Farm Machines, and Build Farm Machines

Finally, ensure that the |tool_type| tools are sourced in your shell setup (i.e. ``.bashrc`` and or ``.bash_profile``) so that any shell can use the corresponding programs.
The environment variables should be visible to any non-interactive shells that are spawned.
You can check this by ensuring that the output of the following command shows that the |tool_type| tools are present in the environment variables (i.e. "|example_var|"):

.. code-block:: bash

    ssh localhost printenv


Install Guestmount
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Finally, you should also install the ``guestmount`` program and ensure it runs properly.
This is needed by a variety of FireSim steps that mount disk images in order to copy in/out results of simulations out of the images.
Most likely you will need to follow the instructions `here <https://askubuntu.com/questions/1046828/how-to-run-libguestfs-tools-tools-such-as-virt-make-fs-without-sudo>`_ to ensure ``guestmount`` doesn't error.

.. warning:: If using ``guestmount``, verify that the command is able to work properly.
   Due to prior issues with ``guestmount`` internally, ensure that your FireSim repository (and all temporary directories)
   does not reside on an NFS mount.


Check Hard File Limit
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Machine: Manager Machine

Check the output of the following command:

.. code-block:: bash

   ulimit -Hn

If the result is greater than or equal to 16384, you can continue on to "Setting up the FireSim Repo". Otherwise, run:

.. code-block:: bash

   echo "* hard nofile 16384" | sudo tee --append /etc/security/limits.conf

Then, reboot your machine.


Setting up the FireSim Repo
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Machine: Manager Machine

We're finally ready to fetch FireSim's sources. This should be done on your Manager Machine. Run:

.. code-block:: bash
   :substitutions:

    git clone https://github.com/firesim/firesim
    cd firesim
    # checkout latest official firesim release
    # note: this may not be the latest release if the documentation version != "stable"
    git checkout |overall_version|

Next, we will bootstrap the machine by installing Miniforge Conda, our software package manager, and set up a default software environment using Conda.
First run the following to see the options to the bootstrap script:

.. code-block:: bash

   ./scripts/machine-launch-script.sh --help

Make sure you understand the options and appropriately run the command.
For example, if you already installed Conda you can use the ``--prefix`` flag to point to an existing installation.
You can also use that same flag to setup Conda in a non-``sudo`` required location.
Next run the ``machine-launch-script.sh``, with the options your setup requires.
Below we will give a few examples on how to run the command (choose the command or modify it accordingly):

.. Warning:: We recommend you re-install Conda in favor of Miniforge Conda (a minimal installation of Conda).

.. tabs::

   .. tab:: With ``sudo`` access (newly install Conda)

      .. code-block:: bash

         sudo ./scripts/machine-launch-script.sh

   .. tab:: Without ``sudo`` access (install Conda to user-specified location)

      .. code-block:: bash

         ./scripts/machine-launch-script.sh --prefix REPLACE_USER_SPECIFIED_LOCATION

   .. tab:: Without ``sudo`` access (use existing Conda)

      .. code-block:: bash

         ./scripts/machine-launch-script.sh --prefix REPLACE_PATH_TO_CONDA

If the option is selected, the script will install Miniforge Conda (https://github.com/conda-forge/miniforge) and create a default environment called ``firesim`` that is used.
**Ensure that you log out of the machine / exit out of the terminal after this step so that** ``.bashrc`` **modifications can apply**.

After re-logging back into the machine, you should be in the ``firesim`` Conda environment (or whatever you decided to name the
environment in the ``machine-launch-script.sh``).
Verify this by running:

.. code-block:: bash

   conda env list

If you are not in the ``firesim`` environment and the environment exists, you can run the following to "activate" or enter the environment:

.. code-block:: bash

   conda activate firesim # or whatever the environment is called

Next run:

.. code-block:: bash

    ./build-setup.sh

The ``build-setup.sh`` script will validate that you are on a tagged branch,
otherwise it will prompt for confirmation.
This will have initialized submodules and installed the RISC-V tools and
other dependencies.

Next, run:

.. code-block:: bash

    source sourceme-manager.sh --skip-ssh-setup

This will perform various environment setup steps, such as adding the RISC-V tools to your
path. Sourcing this the first time will take some time -- however each time after that should be instantaneous.

**Every time you want to use FireSim, you should** ``cd`` **into
your FireSim directory and source this file again with the argument given.**

Final Environment Check
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Finally, lets verify that the environment variables are correctly setup for the guide. Run:

.. code-block:: bash

   echo $PATH

You should see that both the |tool_type| tools are located in the ``PATH`` are are **after**
the conda environment path. Next run:

.. code-block:: bash

   echo $LD_LIBRARY_PATH

You should see that the |tool_type| tools are located on your ``LD_LIBRARY_PATH`` and that there
is no trailing ``:`` (otherwise compilation will error later).

Finally verify that |tool_type| tools are found when running locally through ``ssh``. Run:

.. code-block:: bash

   ssh localhost printenv

Inspect that both the ``PATH`` and ``LD_LIBRARY_PATH`` are setup similarly to running
locally (without ``ssh localhost``).

Completing Setup Using the Manager
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The FireSim manager contains a command that will finish the rest of the FireSim setup process.
To run it, do the following:

.. code-block:: bash
   :substitutions:

    firesim managerinit --platform |platform_name|

It will create initial configuration files, which we will edit in later
sections.

Hit Next to continue with the guide.

FPGA Board Setup
------------------------

FPGA Setup
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. warning:: Currently, FireSim only supports a single type of FPGA (i.e only |fpga_name| FPGAs) installed on a machine.
   This includes not mixing the use of Xilinx Vitis/XRT-enabled FPGAs on the system.

.. Warning:: Power-users can skip this setup and just create the database file listed below by hand if you want to target specific fpgas.

We need to flash the |fpga_name| FPGA(s) SPI flash with a dummy XDMA-enabled design and determine the PCI-e ID (or BDF) associated with the serial number of the FPGA.
First, we need to flash the FPGA's SPI flash with the dummy XDMA-enabled design so that the PCI-e subsystem can be initially configured.
Afterwards, we will generate the mapping from FPGA serial numbers to BDFs.
We provide a set of scripts to do this.

First lets obtain the sample bitstream, let's find the URL to download the file to the machine with the FPGA.
Below find the HWDB entry called |hwdb_entry_name|.

.. literalinclude:: /../deploy/sample-backup-configs/sample_config_hwdb.yaml
   :language: yaml
   :start-after: DOCREF START: Xilinx Alveo HWDB Entries
   :end-before: DOCREF END: Xilinx Alveo HWDB Entries

Look for the ``bitstream_tar: <URL>`` line within |hwdb_entry_name| and keep note of the URL.
We will replace the ``BITSTREAM_TAR`` bash variable below with that URL.
Next, lets unpack the ``tar`` archive and obtain the ``mcs`` file used to program the FPGA SPI flash.

.. code-block:: bash
   :substitutions:

   # unpack the file in any area
   cd ~

   BITSTREAM_TAR=<URL FROM BEFORE>
   tar xvf $BITSTREAM_TAR

   ls |platform_name|

You should see a ``mcs`` file use to program the SPI flash of the FPGA.
Next, lets flash the SPI flash modules of each |fpga_name| in the system with the dummy bitstream.
Open Xilinx Vivado (or Vivado Lab), connect to each FPGA and program the SPI flash.
You can refer to https://www.fpgadeveloper.com/how-to-program-configuration-flash-with-vivado-hardware-manager/ for examples on how to do this for various boards.

Next, **cold reboot** the computer.
This will reconfigure your PCI-E settings such that FireSim can detect the XDMA-enabled bitstream.
After the machine is rebooted, you may need to re-insert the XDMA kernel module.
Then verify that you can see the XDMA module with:

.. code-block:: bash

   lsmod | grep -i xdma

Also, verify that the FPGA programming worked by looking at the ``lspci`` output.
For example, we should see ``Serial controller`` for BDF's that were flashed.

.. code-block:: bash

   lspci | grep -i xilinx

   # example output
   04:00.0 Serial controller: Xilinx Corporation Device 903f (rev ff)
   83:00.0 Serial controller: Xilinx Corporation Device 903f (rev ff)

If you don't see similar output, you might need to **warm reboot** your machine until you see the output.

.. Warning:: Anytime the host computer is rebooted you may need to re-run parts of the setup process (i.e. re-insert XDMA kernel module).
     Before continuing to FireSim simulations after a host computer reboot, ensure that ``cat /proc/devices | grep xdma`` command is successful.
     Also ensure that you see ``Serial controller`` for the BDF of the FPGA you would like to use in ``lspci | grep -i xilinx``.

Next, let's generate the mapping from FPGA serial numbers to the BDF.
Re-enter the FireSim repository and run the following commands to re-setup the repo after reboot.

.. code-block:: bash
   :substitutions:

   cd firesim

   # rerunning this since the machine rebooted
   source sourceme-manager.sh --skip-ssh-setup

Next, open up the ``deploy/config_runtime.yaml`` file and replace the following keys to be the following:

* ``default_platform`` should be |deploy_manager_code|

* ``default_simulation_dir`` should point to a temporary simulation directory of your choice

* ``default_hw_config`` should be |hwdb_entry_name|

Then, run the following command to generate a mapping from a PCI-E BDF to FPGA UID/serial number.

.. code-block:: bash
   :substitutions:

   firesim enumeratefpgas

This will generate a database file in ``/opt/firesim-db.json`` that has this mapping.

Now you're ready to continue with other FireSim setup!





TODO
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Next, install the U250 FPGA as indicated: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Card-Installation-Procedures

We require the following programs/packages installed from the Xilinx website in addition to a physical U250 installation:

* Vivado 2021.1 or 2022.2

* U250 board package (corresponding with Vivado 2021.1 or 2022.2)

  * Ensure you complete the "Installing the Deployment Software" and "Card Bring-Up and Validation" sections in the following link: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Installing-the-Deployment-Software

  * Ensure that the board package is installed to a Vivado accessible location: https://support.xilinx.com/s/article/The-board-file-location-with-the-latest-Vivado-tools?language=en_US


