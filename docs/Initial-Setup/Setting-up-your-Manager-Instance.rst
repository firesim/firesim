Setting up your Manager Instance
================================

Launching a "Manager Instance"
------------------------------

Now, we need to launch a "Manager Instance" that acts as a
"head" node that we will ``ssh`` or ``mosh`` into to work from.
Since we will deploy the heavy lifting to separate ``c4.4xlarge`` and
``f1`` instances later, the Manager Instance can be a relatively cheap instance. In this guide, however,
we will use a ``c4.4xlarge``,
running the AWS FPGA Developer AMI (be sure to subscribe if you have not done so. See :ref:`ami-subscription`).

Head to the `EC2 Management
Console <https://console.aws.amazon.com/ec2/v2/home>`__. In the top
right corner, ensure that the correct region is selected.

To launch a manager instance, follow these steps:

1. From the main page of the EC2 Management Console, click
   ``Launch Instance``. We use an on-demand instance here, so that your
   data is preserved when you stop/start the instance, and your data is
   not lost when pricing spikes on the spot market.
2. When prompted to select an AMI, search in the ``Community AMIs`` tab for
   "FPGA" and select the option that starts with ``FPGA Developer AMI - 1.5.0``.
   **DO NOT USE ANY OTHER VERSION.**
3. When prompted to choose an instance type, select the instance type of
   your choosing. A good choice is a ``c4.4xlarge``.
4. On the "Configure Instance Details" page:

   1. First make sure that the ``firesim`` VPC is selected in the
      drop-down box next to "Network". Any subnet within the ``firesim``
      VPC is fine.
   2. Additionally, check the box for "Protect against accidental
      termination." This adds a layer of protection to prevent your
      manager instance from being terminated by accident. You will need
      to disable this setting before being able to terminate the
      instance using usual methods.
   3. Also on this page, expand "Advanced Details" and in the resulting
      text box, paste the following:

      .. include:: /../scripts/machine-launch-script.sh
         :code: bash

      This will pre-install all of the dependencies needed to run FireSim on your instance.

5. On the next page ("Add Storage"), increase the size of the root EBS
   volume to ~300GB. The default of 150GB can quickly become tight as
   you accumulate large Vivado reports/outputs, large waveforms, XSim outputs,
   and large root filesystems for simulations. You can get rid of the
   small (5GB) secondary volume that is added by default.
6. You can skip the "Add Tags" page, unless you want tags.
7. On the "Configure Security Group" page, select the ``firesim``
   security group that was automatically created for you earlier.
8. On the review page, click the button to launch your instance.

Make sure you select the ``firesim`` key pair that we setup earlier.

Access your instance
~~~~~~~~~~~~~~~~~~~~

We **HIGHLY** recommend using `mosh <https://mosh.org/>`__ instead
of ``ssh`` or using ``ssh`` with a screen/tmux session running on your
manager instance to ensure that long-running jobs are not killed by a
bad network connection to your manager instance. On this instance, the
``mosh`` server is installed as part of the setup script we pasted
before, so we need to first ssh into the instance and make sure the
setup is complete.

In either case, ``ssh`` into your instance (e.g. ``ssh -i firesim.pem centos@YOUR_INSTANCE_IP``) and wait until the
``~/machine-launchstatus`` file contains all the following text:

::

    centos@ip-172-30-2-140.us-west-2.compute.internal:~$ cat machine-launchstatus
    machine launch script started
    machine launch script completed!

Once this line appears, exit and re-``ssh`` into the system. If you want
to use ``mosh``, ``mosh`` back into the system.

Key Setup, Part 2
~~~~~~~~~~~~~~~~~

Now that our manager instance is started, copy the private key that you
downloaded from AWS earlier (``firesim.pem``) to ``~/firesim.pem`` on
your manager instance. This step is required to give the manager access
to the instances it launches for you.

Setting up the FireSim Repo
---------------------------

We're finally ready to fetch FireSim's sources. Run:

::

    git clone https://github.com/firesim/firesim
    cd firesim
    ./build-setup.sh fast

This will have initialized submodules and installed the RISC-V tools and
other dependencies.

Next, run:

::

    source sourceme-f1-manager.sh

This will have initialized the AWS shell, added the RISC-V tools to your
path, and started an ``ssh-agent`` that supplies ``~/firesim.pem``
automatically when you use ``ssh`` to access other nodes. Sourcing this the
first time will take some time -- however each time after that should be instantaneous.
Also, if your ``firesim.pem`` key requires a passphrase, you will be asked for
it here and ``ssh-agent`` should cache it.

**Every time you login to your manager instance to use FireSim, you should ``cd`` into
your firesim directory and source this file again.**


Completing Setup Using the Manager
----------------------------------

The FireSim manager contains a command that will interactively guide you
through the rest of the FireSim setup process. To run it, do the following:

::

    firesim managerinit

This will first prompt you to setup AWS credentials on the instance, which allows
the manager to automatically manage build/simulation nodes. See
https://docs.aws.amazon.com/cli/latest/userguide/tutorial-ec2-ubuntu.html#configure-cli-launch-ec2
for more about these credentials. When prompted, you should specify the same
region that you chose above and set the default output format to ``json``.

Next, it will create initial configuration files, which we will edit in later
sections. Finally, it will prompt you for an email address, which is used to
send email notifications upon FPGA build completion and optionally for
workload completion. You can leave this blank if you do not wish to receive any
notifications, but this is not recommended.

Now you're ready to launch FireSim simulations! Hit Next to learn how to run single-node simulations.
