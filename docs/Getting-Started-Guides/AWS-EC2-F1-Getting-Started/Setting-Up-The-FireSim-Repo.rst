.. _setting-up-firesim-repo:

Setting up the FireSim Repo
===========================

Lets fetch FireSim's sources with Chipyard. Chipyard provides all the necessary target
designs (e.g. RISC-V SoCs) and software (e.g. Linux) used for the rest of this guide.

.. note::

    This guide was built using Chipyard version |cy_docs_version|. It is recommended to
    use the most up-to-date version of Chipyard with this tutorial.

Run:

.. code-block:: bash
    :substitutions:

    git clone https://github.com/ucb-bar/chipyard
    cd chipyard
    # ideally use the main chipyard release instead of this
    git checkout |cy_docs_version|
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

Completing Setup Using the Manager
==================================

The FireSim manager contains a command that will interactively guide you through the
rest of the FireSim setup process. To run it, do the following:

.. code-block:: bash

    firesim managerinit --platform f1

This will first prompt you to setup AWS credentials on the instance, which allows the
manager to automatically manage build/simulation nodes. You can use the same AWS access
key you created when running setup commands on the ``t2.nano`` instance earlier (in
:ref:`run-scripts-t2`). When prompted, you should specify the same region that you've
been selecting thus far (one of ``us-east-1``, ``us-west-2``, ``ap-southeast-2``,
``eu-central-1``, ``eu-west-1`` or ``eu-west-2``) and set the default output format to
``json``.

Next, it will prompt you for an email address, which is used to send email notifications
upon FPGA build completion and optionally for workload completion. You can leave this
blank if you do not wish to receive any notifications, but this is not recommended.
Next, it will create initial configuration files, which we will edit in later sections.

Now you're ready to launch FireSim simulations! Hit Next to learn how to run single-node
simulations.
