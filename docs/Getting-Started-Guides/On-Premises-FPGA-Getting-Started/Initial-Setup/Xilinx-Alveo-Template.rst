FPGA Software Setup
===================

System requirements and Setup
------------------------------

The below sections outline what you need to install to run FireSim on each
machine type in a FireSim cluster. Note that the below three machine types
can all map to a single machine in your setup; in this case, you should follow
all the installation instructions on your single machine.

We highly recommend using Ubuntu 20.04 LTS as the host operating system for
all machine types in an on-premises setup, as this is the OS recommended by
Xilinx. 


Manager Machine
--------------------

The manager machine requires no special setup at this stage. We will clone
the FireSim repo and set up dependencies for the manager in a later step.

Run Farm Machine(s)
----------------------

To set up your Run Farm Machines, please do the following:

First, please refer to the 
`minimum system requirements on the Xilinx website <https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Minimum-System-Requirements>`_
to ensure that your intended Run Farm machine is sufficient for hosting a |fpga_name|.






Build Farm Machines(s)
-------------------------

If you are not planning to run bitstream builds, you can skip this section
for now and return later.




Next, install the U250 FPGA as indicated: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Card-Installation-Procedures

We require the following programs/packages installed from the Xilinx website in addition to a physical U250 installation:

* Vivado 2021.1 or 2022.2

* U250 board package (corresponding with Vivado 2021.1 or 2022.2)

  * Ensure you complete the "Installing the Deployment Software" and "Card Bring-Up and Validation" sections in the following link: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Installing-the-Deployment-Software

  * Ensure that the board package is installed to a Vivado accessible location: https://support.xilinx.com/s/article/The-board-file-location-with-the-latest-Vivado-tools?language=en_US

Importantly, using this FPGA with FireSim requires that you have ``sudo`` **passwordless** access to the machine with the FPGA.
This is needed to flash the FPGA bitstream onto the FPGA.

XDMA Setup
----------

To communicate with the FPGA over PCI-e, we need to install the Xilinx XDMA kernel module.
First, lets install the XDMA kernel module into a FireSim-known location:

.. code-block:: bash

   cd /tmp # or any location you prefer
   git clone https://github.com/Xilinx/dma_ip_drivers
   cd dma_ip_drivers
   git checkout 2022.1.5
   cd XDMA/linux-kernel/xdma
   sudo make clean && sudo make && sudo make install

Next, lets add the kernel module:

.. code-block:: bash

   # the module should be installed in the following location
   # by the `make install` previously run
   sudo insmod /lib/modules/$(uname -r)/extra/xdma.ko poll_mode=1

By default, FireSim will refer to this location to check if the XDMA driver is loaded.
Verify that you can see the XDMA module with:

.. code-block:: bash

   lsmod | grep -i xdma

.. warning:: After the machine is rebooted, you may need to re-insert the XDMA kernel module.

Now you're ready to continue with other FireSim setup!


Setting up your On-Premises Machine
===================================

This tutorial is setting up a single node cluster (i.e. running FPGA bitstream builds and simulations on a single machine) for FireSim use.
This single machine will serve as the "Manager Machine" that acts as a "head" node that all work will be completed on.

Finally, ensure that the |tool_type| tools are sourced in your shell setup (i.e. ``.bashrc`` and or ``.bash_profile``) so that any shell can use the corresponding programs.
The environment variables should be visible to any non-interactive shells that are spawned.
You can check this by ensuring that the output of the following command shows that the |tool_type| tools are present in the environment variables (i.e. "|example_var|"):

.. code-block:: bash

    ssh localhost printenv

Other Miscellaneous Setup
-------------------------

Additionally, you should be able to run ``ssh localhost`` without needing a password.
The FireSim manager program runs all commands by ``ssh``-ing into a BuildFarm/RunFarm machine given an IP address then running the command.
To do so non-interactively, it needs passwordless access to the machines (in our case, ``localhost``) to build/run on.
To safely enable passwordless access, you can first create a unique SSH key and add it to the ``~/.ssh/authorized_keys`` file.
For example, the following instructions will create a SSH key called ``id_rsa_local`` and add it to the authorized keys:

.. code-block:: bash

   cd ~/.ssh

   # create the new key with name `id_rsa_local` and a comment
   # you can use a different name (and modify the comment)
   ssh-keygen -f id_rsa_local -C "@localhost"

   # add the key to the `authorized_keys` file
   cat id_rsa_local.pub >> authorized_keys
   chmod 600 authorized_keys

Next, you should use that key to for ``localhost`` logins by modifying your ``~/.ssh/config`` file so that the SSH agent can use that SSH key.
For example:

.. code-block:: text

   # add the following lines
   Host localhost
      IdentityFile ~/.ssh/id_rsa_local

Finally, you should also install the ``guestmount`` program and ensure it runs properly.
This is needed by a variety of FireSim steps that mount disk images in order to copy in/out results of simulations out of the images.
Most likely you will need to follow the instructions `here <https://askubuntu.com/questions/1046828/how-to-run-libguestfs-tools-tools-such-as-virt-make-fs-without-sudo>`_ to ensure ``guestmount`` doesn't error.

.. warning:: If using ``guestmount``, verify that the command is able to work properly.
   Due to prior issues with ``guestmount`` internally, ensure that your FireSim repository (and all temporary directories)
   does not reside on an NFS mount.

Setting up the FireSim Repo
---------------------------

We're finally ready to fetch FireSim's sources. Run:

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
-----------------------

Finally, lets verify that the environment variables are correctly setup for the tutorial. Run:

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
----------------------------------

The FireSim manager contains a command that will finish the rest of the FireSim setup process.
To run it, do the following:

.. code-block:: bash
   :substitutions:

    firesim managerinit --platform |platform_name|

It will create initial configuration files, which we will edit in later
sections.

Hit Next to continue with the guide.

FPGA Board Setup
===================

FPGA Setup
----------

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
