Setting up your Manager Instance
================================

Launching a "Manager Instance"
------------------------------

.. warning::
    These instructions refer to fields in EC2's new launch instance wizard.
    Refer to `version 1.13.4 <https://docs.fires.im/en/1.13.4/>`__ of the
    documentation for references to the old wizard, being wary that specifics,
    such as the AMI ID selection, may be out of date.

Now, we need to launch a "Manager Instance" that acts as a
"head" node that we will ``ssh`` or ``mosh`` into to work from.
Since we will deploy the heavy lifting to separate ``z1d.2xlarge`` and
``f1`` instances later, the Manager Instance can be a relatively cheap instance.
In this guide, however, we will use a ``c5.4xlarge``,
running the AWS FPGA Developer AMI. (Be sure to subscribe to the AMI
if you have not done so. See :ref:`ami-subscription`. Note that it
might take a few minutes after subscribing to the AMI to be able to
launch instances using it.)

Head to the `EC2 Management
Console <https://console.aws.amazon.com/ec2/v2/home>`__. In the top
right corner, ensure that the correct region is selected.

To launch a manager instance, follow these steps:

#. From the main page of the EC2 Management Console, click
   *Launch Instance ▼* button and click *Launch Instance* in the dropdown that appears. We use an on-demand instance here, so that your
   data is preserved when you stop/start the instance, and your data is
   not lost when pricing spikes on the spot market.
#. In the *Name* field, give the instance a recognizable name, for example ``firesim-manager-1``. This is purely for your own convenience and can also be left blank.
#. In the *Application and OS Images* search box, search for
   ``FPGA Developer AMI - 1.12.2-40257ab5-6688-4c95-97d1-e251a40fd1fc`` and
   select the AMI that appears under the **Community AMIs** tab (there
   should be only one). 
   
   * If you find that there are no results for this search, you can try incrementing the last part of the **version number** (``Z`` in ``X.Y.Z``) in the search string, e.g., ``1.12.2 -> 1.12.3``. Other parts of the search string should be unchanged.

   * **Do not** use `FPGA Developer AMI` from the *AWS Marketplace AMIs* tab, as you will likely get an incorrect version of the AMI.

#. In the *Instance Type* drop-down, select the instance type of
   your choosing. A good choice is a ``c5.4xlarge`` (16 cores, 32 GiB DRAM) or a ``z1d.2xlarge`` (8 cores, 64 GiB DRAM).
#. In the *Key pair (login)* drop-down, select the ``firesim`` key pair we set up earlier.
#. In the *Network settings* drop-down click *edit* and modify the following settings:

   #. Under *VPC - required*, select the ``firesim`` VPC. Any subnet within the ``firesim`` VPC is fine.
   #. Under *Firewall (security groups)*, click *Select existing security
      group* and in the *Common security groups* dropdown that appears, select the ``firesim`` security group that was automatically
      created for you earlier. Do **NOT** select the ``for-farms-only-firesim`` security group that might also be in the list (it is also fine if this group does not appear in your list).

#. In the *Configure storage* section, increase the size of the root
   volume to at least 300GB. The default of 120GB can quickly become too small as
   you accumulate large Vivado reports/outputs, large waveforms, XSim outputs,
   and large root filesystems for simulations. You should remove the
   small (5-8GB) secondary volume that is added by default.
#. In the *Advanced details* drop-down, change the following:

   #. Under *Termination protection*, select Enable. This adds a layer of
      protection to prevent your manager instance from being terminated by
      accident. You will need to disable this setting before being able to
      terminate the instance using usual methods.
   #. Under *User data*, paste the following into the provided textbox:

      .. include:: /../scripts/machine-launch-script.sh
         :code: bash

   When your instance boots, this will install a compatible set of all the dependencies needed to run FireSim on your instance using Conda.

#. Double check your configuration. The most common misconfigurations that may require repeating this process include:

   #. Not selecting the ``firesim`` vpc.
   #. Not selecting the ``firesim`` security group.
   #. Not selecting the ``firesim`` key pair.
   #. Selecting the wrong AMI.

#. Click the orange *Launch Instance* button.


.. warning::
    Recently, some AWS users been having issues with the launch process (after
    you click ``Launch Instance``) getting stuck trying to "Subscribe" to the
    AMI even when the account is already subscribed. We have been able to
    bypass this issue by going to the FPGA Developer AMI page on AWS
    Marketplace, clicking subscribe (even if already subscribed), then clicking
    "Continue to Configuration", then verify the correct AMI version and 
    region are selected and click "Continue to Launch". Finally, change
    the dropdown that says "Launch from Website" to "Launch through EC2" and
    click "Launch". At this point, you will be brought back to
    the usual launch instance page, but the AMI will be pre-selected and you
    will be able to successfully launch at the end, after updating the rest
    of the options as noted above.



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
``/tmp/machine-launchstatus`` file contains all the following text:

.. code-block:: bash

    $ cat /tmp/machine-launchstatus
    machine launch script started
    machine launch script completed

You can also view the live output of the installation process by running ``tail -f /tmp/machine-launchstatus.log``.

Once ``machine launch script completed`` appears in
``/tmp/machine-launchstatus``, exit and re-``ssh`` into the system. If you want
to use ``mosh``, ``mosh`` back into the system.

Key Setup, Part 2
~~~~~~~~~~~~~~~~~

Now that our manager instance is started, copy the private key that you
downloaded from AWS earlier (``firesim.pem``) to ``~/firesim.pem`` on
your manager instance. This step is required to give the manager access
to the instances it launches for you.

.. _setting-up-firesim-repo:

Setting up the FireSim Repo
---------------------------

We're finally ready to fetch FireSim's sources. Run:

.. code-block:: bash

    git clone https://github.com/firesim/firesim
    cd firesim
    # checkout latest official firesim release
    # note: this may not be the latest release if the documentation version != "stable"
    git checkout |overall_version|
    ./build-setup.sh

The ``build-setup.sh`` script will validate that you are on a tagged branch,
otherwise it will prompt for confirmation.
This will have initialized submodules and installed the RISC-V tools and
other dependencies.

Next, run:

.. code-block:: bash

    source sourceme-manager.sh

This will have initialized the AWS shell, added the RISC-V tools to your
path, and started an ``ssh-agent`` that supplies ``~/firesim.pem``
automatically when you use ``ssh`` to access other nodes. Sourcing this the
first time will take some time -- however each time after that should be instantaneous.
Also, if your ``firesim.pem`` key requires a passphrase, you will be asked for
it here and ``ssh-agent`` should cache it.

**Every time you login to your manager instance to use FireSim, you should** ``cd`` **into
your firesim directory and source this file again.**


Completing Setup Using the Manager
----------------------------------

The FireSim manager contains a command that will interactively guide you
through the rest of the FireSim setup process. To run it, do the following:

.. code-block:: bash

    firesim managerinit --platform f1

This will first prompt you to setup AWS credentials on the instance, which allows
the manager to automatically manage build/simulation nodes. You can use the same
AWS access key you created when running setup commands on the ``t2.nano``
instance earlier (in :ref:`run-scripts-t2`). When prompted, you should specify the same
region that you've been selecting thus far (one of ``us-east-1``, ``us-west-2``, or
``eu-west-1``) and set the default output format to ``json``.

Next, it will prompt you for an email address, which is used to
send email notifications upon FPGA build completion and optionally for
workload completion. You can leave this blank if you do not wish to receive any
notifications, but this is not recommended.
Next, it will create initial configuration files, which we will edit in later
sections.

Now you're ready to launch FireSim simulations! Hit Next to learn how to run single-node simulations.
