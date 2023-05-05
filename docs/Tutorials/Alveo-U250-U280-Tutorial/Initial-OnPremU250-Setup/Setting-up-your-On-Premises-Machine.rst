Setting up your On-Premises Machine for Xilinx Alveo U250 FPGA Simulations
==========================================================================

This tutorial is setting up a single node cluster (i.e. running FPGA bitstream builds and simulations on a single machine) for FireSim use.
This single machine will serve as the "Manager Machine" that acts as a "head" node that all work will be completed on.
``sudo`` access **is** needed for the machine being setup.

Xilinx Alveo U250 FPGA Software Installation and Validation
-----------------------------------------------------------

On-Premises FPGA support currently only supports Xilinx Alveo U250 FPGAs (further referred to as a U250 FPGA).

Requirements
~~~~~~~~~~~~

We require a base machine that is able to support the U250 FPGA and running Xilinx Vivado.
Please refer to the minimum system requirements given in the following link: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Minimum-System-Requirements.
Next, install the U250 FPGA as indicated: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Card-Installation-Procedures

We require the following programs/packages installed from the Xilinx website in addition to a physical U250 installation:

* Vivado 2021.1
* U250 board package (corresponding with Vivado 2021.1)
  * Ensure you complete the "Installing the Deployment Software" and "Card Bring-Up and Validation" sections in the following link: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Installing-the-Deployment-Software
  * Ensure that the board package is installed to a Vivado accessible location: https://support.xilinx.com/s/article/The-board-file-location-with-the-latest-Vivado-tools?language=en_US

Other Miscellaneous Setup
~~~~~~~~~~~~~~~~~~~~~~~~~

Finally, ensure that Vivado is sourced in your shell setup (i.e. ``.bashrc`` and or ``.bash_profile``) so that any shell can use the corresponding programs.
The environment variables should be visible to any non-interactive shells that are spawned.
For example, ensure that code similar to the following in your shell setup is commented out (i.e. ``.bashrc`` and or ``.bash_profile``):

.. parsed-literal::

   # If not running interactively, don't do anything
   case $- in
       *i*) ;;
         *) return;;
   esac

You can check this by ensuring that the output of the following command shows that the Vivado tools are present in the environment variables (i.e. ``XILINX_VIVADO``):

.. parsed-literal::

    ssh localhost printenv

Additionally, you should be able to run ``ssh localhost`` without needing a password.
The FireSim manager program runs all commands by ``ssh``-ing into a BuildFarm/RunFarm machine given an IP address then running the command.
To do so non-interactively, it needs passwordless access to the machines (in our case, ``localhost``) to build/run on.

Next, you should have passwordless ``sudo`` access to the machine.

Setting up the FireSim Repo
---------------------------

We're finally ready to fetch FireSim's sources. Run:

.. parsed-literal::

    git clone https://github.com/firesim/firesim
    cd firesim
    # checkout latest official firesim release
    # note: this may not be the latest release if the documentation version != "stable"
    git checkout |version|

Next, we will bootstrap the machine by installing Miniforge Conda, our software package manager, and set up a default software environment using Conda.
First run the following to see the options to the bootstrap script:

.. parsed-literal::

   ./scripts/machine-launch-script.sh --help

Make sure you understand the options and appropriately run the command.
For example, if you already installed Conda you can use the ``--prefix`` flag to point to an existing installation.
You can also use that same flag to setup Conda in a non-``sudo`` required location.
Next run the ``machine-launch-script.sh``, with the options your setup requires.
Below we will give a few examples on how to run the command (choose the command or modify it accordingly):

.. Warning:: We recommend you re-install Conda in favor of Miniforge Conda (a minimal installation of Conda).

.. tabs::

   .. tab:: With ``sudo`` access (newly install Conda)

      .. parsed-literal::

         sudo ./scripts/machine-launch-script.sh

   .. tab:: Without ``sudo`` access (install Conda to user-specified location)

      .. parsed-literal::

         ./scripts/machine-launch-script.sh --prefix REPLACE_USER_SPECIFIED_LOCATION

   .. tab:: Without ``sudo`` access (use existing Conda)

      .. parsed-literal::

         ./scripts/machine-launch-script.sh --prefix REPLACE_PATH_TO_CONDA

If the option is selected, the script will install Miniforge Conda (https://github.com/conda-forge/miniforge) and create a default environment called ``firesim`` that is used.
**Ensure that you log out of the machine / exit out of the terminal after this step so that** ``.bashrc`` **modifications can apply**.

After re-logging back into the machine, you should be in the ``firesim`` Conda environment (or whatever you decided to name the
environment in the ``machine-launch-script.sh``).
Verify this by running:

.. parsed-literal::

   conda env list

If you are not in the ``firesim`` environment and the environment exists, you can run the following to "activate" or enter the environment:

.. parsed-literal::

   conda activate firesim # or whatever the environment is called

Next run:

.. parsed-literal::

    ./build-setup.sh

The ``build-setup.sh`` script will validate that you are on a tagged branch,
otherwise it will prompt for confirmation.
This will have initialized submodules and installed the RISC-V tools and
other dependencies.

Next, run:

.. parsed-literal::

    source sourceme-f1-manager.sh --skip-ssh-setup

This will perform various environment setup steps, such as adding the RISC-V tools to your
path. Sourcing this the first time will take some time -- however each time after that should be instantaneous.

**Every time you want to use FireSim, you should** ``cd`` **into
your FireSim directory and source this file again with the argument given.**

FPGA Setup
----------------

After installing the U250 FPGA using the Xilinx instructions and installing the specific version of Vivado, we need to flash the FPGA with a dummy XDMA-enabled design to finish setup.
First, lets install the XDMA kernel module in a FireSim known location:

.. parsed-literal::

   cd /tmp
   git clone https://github.com/Xilinx/dma_ip_drivers
   cd dma_ip_drivers
   git checkout 2022.1.5
   cd XDMA/linux-kernel/xdma
   make clean && make && make install

Next, lets add the kernel module:

.. parsed-literal::

   sudo insmod /lib/modules/$(uname -r)/extra/xdma.ko poll_mode=1

Next, lets flash the FPGAs with the dummy bitstream.
Open the ``sample_config_hwdb.yaml`` file, find the HWDB entry called ``alveou250_firesim_rocket_singlecore_no_nic``, and retrieve the ``bit:`` field URL.

.. parsed-literal::

   # open sample_config_hwdb.yaml
   vim deploy/sample-backup-configs/sample_config_hwdb.yaml

   # find alveou250_firesim_rocket_singlecore_no_nic
   # save URL of bit: field
   # i.e. `bit: <SAVE THIS URL>`

Once the URL is saved, let's use this bitstream to flash any FPGA that you would like to use for FireSim.
First, lets get the BDF's of any FPGA you want to use with FireSim.

.. parsed-literal::

   # determine BDF of FPGA that you want to use / re-flash
   lspci | grep -i xilinx

   # example output:
   # 04:00.0 Processing accelerators: Xilinx Corporation Device 5004
   # 04:00.1 Processing accelerators: Xilinx Corporation Device 5005
   # 83:00.0 Processing accelerators: Xilinx Corporation Device 5004
   # 83:00.1 Processing accelerators: Xilinx Corporation Device 5005

   # BDF would be 0000:04:00.0 if you want to flash the '04' FPGA

Next, lets flash the FPGA with that bitstream and the BDF chosen (repeat this for any other BDF's wanted):

.. parsed-literal::

   wget -O /tmp/firesim.bit <URL SAVE FROM PREVIOUSLY>
   EXTENDED_DEVICE_BDF1=<YOUR BDF HERE> ./platforms/xilinx_alveo_u250/scripts/program_fpga.sh /tmp/firesim.bit au250

Next, **warm reboot** the computer.

After the machine is rebooted, you may need to re-insert the XDMA kernel module.
Then verify that you can see the XDMA module with:

.. parsed-literal::

   cat /proc/devices | grep xdma

Also, verify that the FPGA programming worked by seeing if the ``lspci`` output has changed.
For example, we should see ``Serial controller`` for BDF's that were flashed.


.. parsed-literal::

   lspci | grep -i xilinx

   # example output if only the 0000:04:00.0 FPGA was programmed
   04:00.0 Serial controller: Xilinx Corporation Device 903f (rev ff)
   83:00.0 Processing accelerators: Xilinx Corporation Device 5004
   83:00.1 Processing accelerators: Xilinx Corporation Device 5005

.. Warning:: Anytime the host computer is rebooted you may need to re-run parts of the setup process (i.e. re-insert XDMA kernel module).
     Before continuing to FireSim simulations after a host computer reboot, ensure that the previously mentioned ``cat /proc/devices | grep xdma`` command is successful.

Final Environment Check
-----------------------

Finally, lets verify that the environment variables are correctly setup for the tutorial. Run:

.. parsed-literal::

   echo $PATH

You should see that the Xilinx Vivado tools are located in the ``PATH`` are are **after**
the conda environment path. Next run:

.. parsed-literal::

   echo $LD_LIBRARY_PATH

You should see that there is no trailing ``:`` (otherwise compilation will error later).

Finally verify that Xilinx Vivado tools are found when running locally through ``ssh``. Run:

.. parsed-literal::

   ssh localhost printenv

Inspect that both the ``PATH`` and ``LD_LIBRARY_PATH`` are setup similarly to running
locally (without ``ssh localhost``).

Completing Setup Using the Manager
----------------------------------

The FireSim manager contains a command that will finish the rest of the FireSim setup process.
To run it, do the following:

.. parsed-literal::

    firesim managerinit --platform vitis

It will create initial configuration files, which we will edit in later
sections.

Now you're ready to launch FireSim simulations! Hit Next to learn how to run single-node simulations.
