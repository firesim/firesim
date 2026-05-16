.. _setting-up-firesim-repo:

Setting up the FireSim Repo
===========================

Lets fetch FireSim's sources with Chipyard. Chipyard provides all the necessary target
designs (e.g. RISC-V SoCs) and software (e.g. Linux) used for the rest of this guide.

.. important::

    The commands below should be run **on your FireSim manager instance** (the EC2
    instance you launched in the previous section), not on your local laptop or another
    machine. Even if you already have a Chipyard checkout elsewhere, you need a fresh
    clone on the manager instance for the AWS flow.

.. warning::

    The AWS EC2 F2 flow requires a Chipyard version whose FireSim submodule includes
    ``f2`` platform support. The pinned commit referenced in some versions of these docs
    (|cy_docs_version|) was used when this guide was originally authored and **may not
    include F2 support**. Even the Chipyard ``main`` branch may temporarily lag behind
    if its pinned FireSim submodule has not yet been updated for F2. Use the
    verification step after setup to confirm ``f2`` is available before proceeding.

Run:

.. code-block:: bash

    git clone https://github.com/ucb-bar/chipyard
    cd chipyard
    git checkout main
    ./build-setup.sh

This will have initialized submodules and installed the RISC-V tools and other
dependencies.

Next, run:

.. code-block:: bash

    cd sims/firesim
    source sourceme-manager.sh

This will have initialized the AWS shell, added the RISC-V tools to your path, and
started an ``ssh-agent`` that supplies ``~/firesim.pem`` automatically when you use
``ssh`` to access other nodes. Sourcing this the first time will take some time --
however each time after that should be instantaneous. Also, if your ``firesim.pem`` key
requires a passphrase, you will be asked for it here and ``ssh-agent`` should cache it.

**Every time you login to your manager instance to use FireSim, you should** ``cd``
**into your firesim directory and source this file again.**

.. tip::

    After sourcing, verify that your checkout supports the F2 platform:

    .. code-block:: bash

        firesim managerinit --help

    Confirm that ``f2`` appears in the list of valid ``--platform`` choices. If it does
    not, your current Chipyard/FireSim checkout does not include F2 platform support —
    see the troubleshooting note in the next section.

.. note::

    If you move or delete the Chipyard directory after sourcing
    ``sourceme-manager.sh``, your current shell may still have stale paths cached.
    Open a fresh shell (or log out and back in) to resolve this.

Completing Setup Using the Manager
==================================

The FireSim manager contains a command that will interactively guide you through the
rest of the FireSim setup process. To run it, do the following:

.. code-block:: bash

    firesim managerinit --platform f2

.. note::

    **Troubleshooting: f2 is not listed as a valid platform.**
    The FireSim submodule pinned by your Chipyard branch may not yet include F2 support
    (these docs may describe features ahead of the pinned submodule version). To
    recover, update the submodule and rebuild from a **fresh shell**:

    .. code-block:: bash

        # Start a fresh shell, then:
        cd ~/chipyard               # adjust to your Chipyard location
        source env.sh               # sets RISCV and other required variables

        cd sims/firesim
        git fetch origin
        git checkout main
        git pull --ff-only

        ./build-setup.sh --library
        source sourceme-manager.sh
        firesim managerinit --help  # verify f2 appears

    Confirm that ``f2`` appears before re-running
    ``firesim managerinit --platform f2``.

    **Do not** run ``git submodule update`` from the Chipyard root after this recovery —
    it will reset ``sims/firesim`` back to the (possibly F2-incompatible) revision
    pinned by your Chipyard branch.

This will first prompt you to setup AWS credentials on the instance, which allows the
manager to automatically manage build/simulation nodes. You can use the same AWS access
key you created when running setup commands on the ``t2.nano`` instance earlier (in
:ref:`run-scripts-t2`). When prompted, you should specify the same region that you've
been selecting thus far (e.g. ``us-east-1``, ``us-west-2``, etc.) and set the default output format to
``json``.

Next, it will prompt you for an email address, which is used to send email notifications
upon FPGA build completion and optionally for workload completion. You can leave this
blank if you do not wish to receive any notifications, but this is not recommended.
Next, it will create initial configuration files, which we will edit in later sections.

Now you're ready to launch FireSim simulations! Hit Next to learn how to run single-node
simulations.
