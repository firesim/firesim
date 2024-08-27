.. |fpga_name| replace:: Xilinx Vitis-enabled U250

.. |vitis_version| replace:: 2022.1

.. |vitis_link| replace:: https://www.xilinx.com/products/design-tools/vitis/vitis-whats-new.html#20221

.. |platform_name| replace:: vitis

.. |tool_type| replace:: Xilinx XRT/Vitis

.. |example_var| replace:: XILINX_XRT

.. |manager_machine| replace:: **Manager Machine**

.. |build_farm_machine| replace:: **Build Farm Machines**

.. |run_farm_machine| replace:: **Run Farm Machines**

Initial Setup/Installation
==========================

.. warning::

    ⚠️ **We highly recommend using the XDMA-based U250 flow instead of this Vitis-based
    flow. You can find the XDMA-based flow here:** :ref:`u250-standard-flow`. The
    Vitis-based flow does not support DMA-based FireSim bridges (e.g., TracerV,
    Synthesizable Printfs, etc.), while the XDMA-based flows support all FireSim
    features. If you're unsure, use the XDMA-based U250 flow instead:
    :ref:`u250-standard-flow`

FPGA and Tool Setup
-------------------

Requirements and Installations
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

We require a base machine that is able to support a |fpga_name| and running Xilinx
Vitis. For the purposes of this guide, we assume you are running with a |fpga_name|.
Please refer to the minimum system requirements given in the following link:
https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Minimum-System-Requirements.
``sudo`` access is not needed for the machine except for when the |fpga_name| and
corresponding software is installed.

Next, install the |fpga_name| as indicated:
https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Card-Installation-Procedures

We require the following programs/packages installed from the Xilinx website in addition
to a physical |fpga_name| installation:

- Xilinx Vitis |vitis_version|

  - Installation link: |vitis_link|

- Xilinx XRT and |fpga_name| board package (corresponding with Vitis |vitis_version|)

  - Ensure you complete the "Installing the Deployment Software" and "Card Bring-Up and
    Validation" sections in the following link:
    https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Installing-the-Deployment-Software

Setup Validation
~~~~~~~~~~~~~~~~

After installing the |fpga_name| using the Xilinx instructions and installing the
specific versions of Vitis/XRT, let's verify that the |fpga_name| can be used for
emulations. Ensure that you can run the following XRT commands without errors:

.. code-block:: bash
    :substitutions:

    xbutil examine # obtain the BDF associated with your installed |fpga_name|
    xbutil validate --device <CARD_BDF_INSTALLED> --verbose

The ``xbutil validate`` command runs simple tests to ensure that the FPGA can be
properly flashed with a bitstream by using XRT.

.. warning::

    Anytime the host computer is rebooted you may need to re-run parts of the setup
    process (i.e. re-flash the shell). Before continuing to FireSim simulations after a
    host computer reboot, ensure that the previously mentioned ``xbutil`` command is
    successful.

Now you're ready to continue with other FireSim setup!

Setting up your On-Premises Machine
-----------------------------------

This guide will walk you through setting up a single node cluster (i.e. running FPGA
bitstream builds and simulations on a single machine) for FireSim use. This single
machine will serve as the "Manager Machine" that acts as a "head" node that all work
will be completed on.

Finally, ensure that the |tool_type| tools are sourced in your shell setup (i.e.
``.bashrc`` and or ``.bash_profile``) so that any shell can use the corresponding
programs. The environment variables should be visible to any non-interactive shells that
are spawned. You can check this by ensuring that the output of the following command
shows that the |tool_type| tools are present in the environment variables (i.e.
"|example_var|"):

.. code-block:: bash

    ssh localhost printenv

Setting up the FireSim Repo
~~~~~~~~~~~~~~~~~~~~~~~~~~~

We're finally ready to fetch FireSim's sources. Run:

.. code-block:: bash
    :substitutions:

    git clone https://github.com/ucb-bar/chipyard
    cd chipyard
    # ideally use the main chipyard release instead of this
    git checkout |cy_docs_version|

Next run:

.. code-block:: bash

    ./build-setup.sh

This will have initialized submodules and installed the RISC-V tools and other
dependencies.

Next, run:

.. code-block:: bash

    cd sims/firesim
     source sourceme-manager.sh --skip-ssh-setup

This will perform various environment setup steps, such as adding the RISC-V tools to
your path. Sourcing this the first time will take some time -- however each time after
that should be instantaneous.

**Every time you want to use FireSim, you should** ``cd`` **into your FireSim directory
and source this file again with the argument given.**

Completing Setup Using the Manager
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

The FireSim manager contains a command that will finish the rest of the FireSim setup
process. To run it, do the following:

.. code-block:: bash
    :substitutions:

    firesim managerinit --platform |platform_name|

It will create initial configuration files, which we will edit in later sections.

Hit Next to continue with the guide.
