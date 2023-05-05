Setting up your On-Premises Machine
===================================

This tutorial is setting up a single node cluster (i.e. running FPGA bitstream builds and simulations on a single machine) for FireSim use.
This single machine will serve as the "Manager Machine" that acts as a "head" node that all work will be completed on.
``sudo`` access is not needed for the machine being setup except for when the U250 FPGA and corresponding software is installed.

Xilinx Alveo FPGA Software Installation and Validation
------------------------------------------------------

On-Premises FPGA support currently only supports Xilinx Alveo U250 FPGAs (further referred to as a U250 FPGA).

Requirements
~~~~~~~~~~~~

We require a base machine that is able to support the U250 FPGA and running Xilinx Vivado/Vitis.
Please refer to the minimum system requirements given in the following link: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Minimum-System-Requirements.
Next, install the U250 FPGA as indicated: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Card-Installation-Procedures

We require the following programs/packages installed from the Xilinx website in addition to a physical U250 installation:

* Xilinx Vitis 2022.1
  * Installation link: https://www.xilinx.com/products/design-tools/vitis/vitis-whats-new.html#20221
* Xilinx XRT and U250 board package (corresponding with Vitis 2022.1)
  * Ensure you complete the "Installing the Deployment Software" and "Card Bring-Up and Validation" sections in the following link: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Installing-the-Deployment-Software

Setup Validation
~~~~~~~~~~~~~~~~

After installing the U250 FPGA using the Xilinx instructions and installing the specific versions of Vitis/XRT, let's verify that the U250 FPGA can be used for emulations.
Ensure that you can run the following XRT commands without errors:

.. parsed-literal::

   xbutil examine # obtain the BDF associated with your installed U250 FPGA
   xbutil validate --device <CARD_BDF_INSTALLED> --verbose

The ``xbutil validate`` command runs simple tests to ensure that the FPGA can be properly flashed with a bitstream by using XRT.

.. Warning:: Anytime the host computer is rebooted you may need to re-run parts of the setup process (i.e. re-flash the shell).
     Before continuing to FireSim simulations after a host computer reboot, ensure that the previously mentioned ``xbutil`` command is successful.

Finally, ensure that the XRT/Vitis tools are sourced in your shell setup (i.e. ``.bashrc`` and or ``.bash_profile``) so that any shell can use the corresponding programs.
The environment variables should be visible to any non-interactive shells that are spawned.
You can check this by ensuring that the output of the following command shows that the XRT/Vitis tools are present in the environment variables (i.e. ``XILINX_XRT``):

.. parsed-literal::

    ssh localhost printenv

Other Miscellaneous Setup
-------------------------

Additionally, you should be able to run ``ssh localhost`` without needing a password.
The FireSim manager program runs all commands by ``ssh``-ing into a BuildFarm/RunFarm machine given an IP address then running the command.
To do so non-interactively, it needs passwordless access to the machines (in our case, ``localhost``) to build/run on.

Finally, if you are running this tutorial without ``sudo`` access you should also install the ``guestmount`` program and ensure it runs properly.
This is needed by a variety of FireSim steps that mount disk images in order to copy in/out results of simulations out of the images.
Most likely you will need to follow the instructions `here <https://askubuntu.com/questions/1046828/how-to-run-libguestfs-tools-tools-such-as-virt-make-fs-without-sudo>`_ to ensure ``guestmount`` doesn't error.

.. warning:: If using ``guestmount``, verify that the command is able to work properly.
   Due to prior issues with ``guestmount`` internally, ensure that your FireSim repository (and all temporary directories)
   does not reside on an NFS mount.

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

Final Environment Check
-----------------------

Finally, lets verify that the environment variables are correctly setup for the tutorial. Run:

.. parsed-literal::

   echo $PATH

You should see that both the Xilinx Vitis and XRT tools are located in the ``PATH`` are are **after**
the conda environment path. Next run:

.. parsed-literal::

   echo $LD_LIBRARY_PATH

You should see that the XRT tools are located on your ``LD_LIBRARY_PATH`` and that there
is no trailing ``:`` (otherwise compilation will error later).

Finally verify that Xilinx Vitis and XRT tools are found when running locally through ``ssh``. Run:

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
