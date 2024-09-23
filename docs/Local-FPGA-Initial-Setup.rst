.. _initial-local-setup:

Local FPGA System Setup
=======================

The below sections outline what you need to install to run FireSim on each machine type
in a FireSim cluster. **Note that the below three machine types can all map to a single
machine in your setup**; in this case, you should follow all the installation
instructions on your single machine, without duplication (i.e., don't re-run a step on
the same machine if it is required on multiple machine types).

.. warning::

    **We highly recommend using Ubuntu 20.04 LTS as the host operating system for all
    machine types in an on-premises setup, as this is the OS recommended by Xilinx.**

The following steps are separated into steps that require ``sudo`` and steps that do
not. After initial setup with ``sudo``, FireSim doesn't need ``sudo`` access. In many
cases with a shared machine, ``sudo``-based setup is already completed and thus users
should continue onto the Non-``sudo``-based setup.

``sudo`` Setup
--------------

**1. Install/enable FireSim scripts to new** ``firesim`` **Linux group**

.. note::

    These scripts are used by the FireSim manager and other FireSim tooling (i.e.
    FireMarshal) to avoid needing ``sudo`` access.

**Machines:** Manager Machine, Run Farm Machines, Build Farm Machines.

First, let's clone a temporary version of FireSim with the scripts within it:

.. code-block:: bash
    :substitutions:

    cd ~/     # or any scratch directory
    mkdir firesim-script-installs
    cd firesim-script-installs
    git clone https://github.com/firesim/firesim
    cd firesim
    # checkout latest official firesim release
    # note: this may not be the latest release if the documentation version != "stable"
    git checkout |overall_version|

Next, copy the required scripts to ``/usr/local/bin``:

.. code-block:: bash

    sudo cp deploy/sudo-scripts/* /usr/local/bin
    sudo cp platforms/xilinx_alveo_u250/scripts/* /usr/local/bin

Now we can delete the temporary clone:

.. code-block:: bash

    rm -rf ~/firesim-script-installs    # or the temp. dir. created previously

Next, lets change the permissions of the scripts and them to a new ``firesim`` Linux
group.

.. code-block:: bash

    sudo addgroup firesim
    sudo chmod 755 /usr/local/bin/firesim*
    sudo chgrp firesim /usr/local/bin/firesim*

Next, lets allow the ``firesim`` Linux group to run the pre-installed commands.
Enter/create the following file with `sudo`:

.. code-block:: bash

    sudo visudo /etc/sudoers.d/firesim

Then add the following lines:

.. code-block:: bash

    %firesim ALL=(ALL) NOPASSWD: /usr/local/bin/firesim-*

Then change the permissions of the file:

.. code-block:: bash

    sudo chmod 400 /etc/sudoers.d/firesim

This allows only users in the ``firesim`` group to execute the scripts.

**2. Add your user to the** ``firesim`` **group**

**Machines:** Manager Machine, Run Farm Machines, Build Farm Machines.

Next, add all user who want to use FireSim to the ``firesim`` group that you created.
Make sure to replace ``YOUR_USER_NAME`` with the user to run simulations with:

.. code-block:: bash

    sudo usermod -a -G firesim YOUR_USER_NAME

Finally, verify that the user can access the FireSim installed scripts by running:

.. code-block:: bash

    sudo -l

The output should look similar to this:

.. code-block:: bash

    User YOUR_USER_NAME may run the following commands on MACHINE_NAME:
        (ALL) NOPASSWD: /usr/local/bin/firesim-*

**3. Install Vivado Lab and Cable Drivers**

**Machines:** Run Farm Machines.

Go to the `Xilinx Downloads Website <https://www.xilinx.com/support/download.html>`_ and
download ``Vivado 2023.1: Lab Edition - Linux``.

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

**4. Install the Xilinx XDMA and XVSEC drivers**

**Machines:** Run Farm Machines.

.. warning::

    These commands will need to be re-run everytime the kernel is updated (normally
    whenever the machine is rebooted).

First, run the following to clone the XDMA kernel module source:

.. code-block:: bash

    cd ~/   # or any directory you would like to work from
    git clone https://github.com/Xilinx/dma_ip_drivers
    cd dma_ip_drivers
    git checkout 0e8d321
    cd XDMA/linux-kernel/xdma

.. note::

    If using the RHS Research Nitefury II board, do the following: The directory you are
    now in contains the XDMA kernel module. For the Nitefury to work, we will need to
    make one modification to the driver. Find the line containing ``#define
    XDMA_ENGINE_XFER_MAX_DESC``. Change the value on this line from ``0x800`` to ``16``.
    Then, build and install the driver:

.. code-block:: bash

    sudo make install

Now, test that the module can be inserted:

.. code-block:: bash

    sudo insmod $(find /lib/modules/$(uname -r) -name "xdma.ko") poll_mode=1
    lsmod | grep -i xdma

The second command above should have produced output indicating that the XDMA driver is
loaded.

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

The second command above should have produced output indicating that the XVSEC driver is
loaded.

Also, make sure you get output for the following (usually,
``/usr/local/sbin/xvsecctl``):

.. code-block:: bash

    which xvsecctl

**5. Install sshd**

**Machines:** Manager Machine, Run Farm Machines, and Build Farm Machines

On Ubuntu, install ``openssh-server`` like so:

.. code-block:: bash

    sudo apt install openssh-server

**7. Check Hard File Limit**

**Machine:** Manager Machine

Check the output of the following command:

.. code-block:: bash

    ulimit -Hn

If the result is greater than or equal to 16384, you can continue. Otherwise, run:

.. code-block:: bash

    echo "* hard nofile 16384" | sudo tee --append /etc/security/limits.conf

Then, reboot your machine.

**8. Install your FPGA**

The starter tutorials will guide you through specific installation instructions for each
FPGA.

Non-``sudo`` Setup
------------------

**1. Fix default** ``.bashrc``

**Machines:** Manager Machine, Run Farm Machines, Build Farm Machines.

We need various parts of the ``~/.bashrc`` file to execute even in non-interactive mode.
To do so, edit your ``~/.bashrc`` file so that the following section is removed:

.. code-block:: bash

    # If not running interactively, don't do anything
    case $- in
         *i*) ;;
           *) return;;
    esac

**2. Set up SSH Keys**

**Machines:** Manager Machine.

On the manager machine, generate a keypair that you will use to ssh from the manager
machine into the manager machine (ssh localhost), run farm machines, and build farm
machines:

.. code-block:: bash

    cd ~/.ssh
    ssh-keygen -t ed25519 -C "firesim.pem" -f firesim.pem
    [create passphrase]

Next, add this key to the ``authorized_keys`` file on the manager machine:

.. code-block:: bash

    cd ~/.ssh
    cat firesim.pem.pub >> authorized_keys
    chmod 0600 authorized_keys

You should also copy this public key into the ``~/.ssh/authorized_keys`` files on all of
your Run Farm and Build Farm Machines.

Returning to the Manager Machine, let's set up an ``ssh-agent``:

.. code-block:: bash

    cd ~/.ssh
    ssh-agent -s > AGENT_VARS
    source AGENT_VARS
    ssh-add firesim.pem

If you reboot your machine (or otherwise kill the ``ssh-agent``), you will need to
re-run the above four commands before using FireSim. If you open a new terminal (and
``ssh-agent`` is already running), you can simply run ``source ~/.ssh/AGENT_VARS``.

Finally, confirm that you can now ``ssh localhost`` and ssh into your Run Farm and Build
Farm Machines without being prompted for a passphrase.

**3. Verify Run Farm Machine environment**

**Machines:** Manager Machine and Run Farm Machines

Finally, let's ensure that the Xilinx Vivado Lab tools are properly sourced in your
shell setup (i.e. ``.bashrc``) so that any shell on your Run Farm Machines can use the
corresponding programs. The environment variables should be visible to any
non-interactive shells that are spawned.

You can check this by running the following on the Manager Machine, replacing
``RUN_FARM_IP`` with ``localhost`` if your Run Farm machine and Manager machine are the
same machine, or replacing it with the Run Farm machine's IP address if they are
different machines.

.. code-block:: bash

    ssh RUN_FARM_IP printenv

Ensure that the output of the command shows that the Xilinx Vivado Lab tools are present
in the printed environment variables (i.e., ``PATH``).

If you have multiple Run Farm machines, you should repeat this process for each Run Farm
machine, replacing ``RUN_FARM_IP`` with a different Run Farm Machine's IP address.

Congratulations! We've now set up your machine/cluster to run simulations.
