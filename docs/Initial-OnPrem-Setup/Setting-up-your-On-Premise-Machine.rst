Setting up your On-Premise Machine
==================================

This tutorial is setting up a single node cluster (i.e. running FPGA bitstream builds and simulations on a single machine) for FireSim use.
This single machine will serve as the "Manager Machine" that acts as a "head" node that all work will be completed on.
``sudo`` access is not needed for the machine being setup except for when the U250 FPGA and corresponding software is installed.

Xilinx Alveo FPGA Software Installation and Validation
------------------------------------------------------

On-Premise FPGA support currently only supports Xilinx Alveo U250 FPGAs (further referred to as a U250 FPGA).

Requirements
~~~~~~~~~~~~

We require the following programs/packages installed from the Xilinx website in addition to a physical U250 installation:

* Xilinx Vitis 2022.1
* Xilinx XRT (corresponding with Vitis 2022.1)
* Xilinx U250 board package (corresponding with Vitis 2022.1)

Setup Validation
----------------

After installing the U250 FPGA using the Xilinx instructions and installing the specific versions of Vitis/XRT, let's verify that the U250 FPGA can be used for emulations.
Ensure that you can run the following XRT commands without errors:

.. parsed-literal::

   xbutil examine # obtain the BDF associated with your installed U250 FPGA
   xbutil validate --device <CARD_BDF_INSTALLED> --verbose

The ``xbutil validate`` command runs simple tests to ensure that the FPGA can be properly flashed with a bitstream by using XRT.

.. Warning:: Anytime the host computer is rebooted you may need to re-run parts of the setup process (i.e. re-flash the shell).
     Before continuing to FireSim simulations after a host computer reboot, ensure that the previously mentioned ``xbutil`` command is successful.

Setting up the FireSim Repo
---------------------------

We're finally ready to fetch FireSim's sources. Run:

.. parsed-literal::

    git clone https://github.com/firesim/firesim
    cd firesim
    # checkout latest official firesim release
    # note: this may not be the latest release if the documentation version != "stable"
    git checkout |version|

Next, we will bootstrap the machine by installing Miniforge Conda, our software package manager, and setup a default software environment using Conda.
Run:

.. parsed-literal::

   ./scripts/machine-launch-script.sh

This will install Miniforge Conda (https://github.com/conda-forge/miniforge) and create a default environment called ``firesim`` that is used.
**Ensure that you log out of the machine / exit out of the terminal after this step so that ``.bashrc`` modifications can apply**.

.. Warning:: If you already have Conda installed, you can look at the help text of ``machine-launch-script.sh`` to see extra options given
   to avoid re-installation. We recommend you re-install Conda in favor of Miniforge Conda (a minimal installation of Conda).

Next run:

.. parsed-literal::

    ./build-setup.sh

The ``build-setup.sh`` script will validate that you are on a tagged branch,
otherwise it will prompt for confirmation.
This will have initialized submodules and installed the RISC-V tools and
other dependencies.

Next, run:

::

    source sourceme-f1-manager.sh --skip-ssh-setup

This will have initialized the AWS shell, added the RISC-V tools to your
path. Sourcing this the first time will take some time -- however each time after that should be instantaneous.

**Every time you want to use FireSim, you should ``cd`` into
your FireSim directory and source this file again with the argument given.**

Completing Setup Using the Manager
----------------------------------

The FireSim manager contains a command that will finish the rest of the FireSim setup process.
To run it, do the following:

::

    firesim managerinit --platform vitis

It will create initial configuration files, which we will edit in later
sections.

Now you're ready to launch FireSim simulations! Hit Next to learn how to run single-node simulations.
