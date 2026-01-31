Setting up your Manager Instance
================================

Launching a "Manager Instance"
------------------------------

.. warning::

    These instructions refer to fields in EC2's new launch instance wizard. Refer to
    `version 1.13.4 <https://docs.fires.im/en/1.13.4/>`__ of the documentation for
    references to the old wizard, being wary that specifics, such as the AMI ID
    selection, may be out of date.

Now, we need to launch a "Manager Instance" that acts as a "head" node that we will
``ssh`` or ``mosh`` into to work from. Since we will deploy the heavy lifting to
separate ``z1d.2xlarge`` and ``f2`` instances later, the Manager Instance can be a
relatively cheap instance. In this guide, however, we will use a ``c5.4xlarge``, running
the AWS FPGA Developer AMI. 

To launch a manager instance, follow these steps:

1. Click on *View Purchase Options* on the `AWS Marketplace page for the FPGA Developer AMI <https://aws.amazon.com/marketplace/pp/prodview-tcl7sjgreh6bq>`__.
   If already subscribed, then click *Launch your software*. Otherwise, Subscribe and then click *Launch your software*.
2. In the *Launch FPGA Developer AMI (Ubuntu)* window:

   1. Set the launch method to *Launch from EC2 Console*. 
   2. Set the version to *1.17.0*.
      **If you do not change the version, you will likely get an incorrect version of the AMI.**
   
   3. Select the region that corresponds to the region you selected in :ref:`configuring-required-infrastructure-in-your-aws-account`.
   4. Click *Launch from EC2*. It should open a new tab in your browser with the launch instance page.
   
3. In the *Name* field, give the instance a recognizable name, for example
   ``firesim-manager-1``. This is purely for your own convenience and can also be left
   blank.
4. In the *Instance Type* drop-down, select the instance type of your choosing. A good
   choice is a ``c5.4xlarge`` (16 cores, 32 GiB DRAM) or a ``z1d.2xlarge`` (8 cores, 64
   GiB DRAM).
5. In the *Key pair (login)* drop-down, select the ``firesim`` key pair we set up
   earlier.
6. In the *Network settings* drop-down click *edit* and modify the following settings:

   1. Under *VPC - required*, select the ``firesim`` VPC. Any subnet within the
      ``firesim`` VPC is fine.
   2. Under *Firewall (security groups)*, click *Select existing security group* and in
      the *Common security groups* dropdown that appears, select the ``firesim``
      security group that was automatically created for you earlier. Do **NOT** select
      the ``for-farms-only-firesim`` security group that might also be in the list (it
      is also fine if this group does not appear in your list).

7.  In the *Configure storage* section, increase the size of the root volume to at least
   300GB. The default of 120GB can quickly become too small as you accumulate large
   Vivado reports/outputs, large waveforms, XSim outputs, and large root filesystems for
   simulations. You should remove the small (5-8GB) secondary volume that is added by
   default.
8.  In the *Advanced details* drop-down, change the following:

   1. Under *Termination protection*, select Enable. This adds a layer of protection to
      prevent your manager instance from being terminated by accident. You will need to
      disable this setting before being able to terminate the instance using usual
      methods.
   2. Under *User data*, paste the following into the provided textbox:

      .. include:: /../scripts/machine-launch-script.sh
          :code: bash

   When your instance boots, this will install a compatible set of all the dependencies
   needed to run FireSim on your instance using Conda.

10. Double check your configuration. The most common misconfigurations that may require
   repeating this process include:

   1. Not selecting the ``firesim`` vpc.
   2. Not selecting the ``firesim`` security group.
   3. Not selecting the ``firesim`` key pair.
   4. Selecting the wrong AMI.

11. Click the orange *Launch Instance* button.

.. warning::

    Recently, some AWS users been having issues with the launch process (after you click
    ``Launch Instance``) getting stuck trying to "Subscribe" to the AMI even when the
    account is already subscribed. We have been able to bypass this issue by going to
    the FPGA Developer AMI page on AWS Marketplace, clicking subscribe (even if already
    subscribed), then clicking "Continue to Configuration", then verify the correct AMI
    version and region are selected and click "Continue to Launch". Finally, change the
    dropdown that says "Launch from Website" to "Launch through EC2" and click "Launch".
    At this point, you will be brought back to the usual launch instance page, but the
    AMI will be pre-selected and you will be able to successfully launch at the end,
    after updating the rest of the options as noted above.

Access your instance
~~~~~~~~~~~~~~~~~~~~

We **HIGHLY** recommend using `mosh <https://mosh.org/>`__ instead of ``ssh`` or using
``ssh`` with a screen/tmux session running on your manager instance to ensure that
long-running jobs are not killed by a bad network connection to your manager instance.
On this instance, the ``mosh`` server is installed as part of the setup script we pasted
before, so we need to first ssh into the instance and make sure the setup is complete.

In either case, ``ssh`` into your instance (e.g. ``ssh -i firesim.pem
ubuntu@YOUR_INSTANCE_IP``) and wait until the ``/tmp/machine-launchstatus`` file
contains all the following text:

.. code-block:: bash

    $ cat /tmp/machine-launchstatus
    machine launch script started
    machine launch script completed

You can also view the live output of the installation process by running ``tail -f
/tmp/machine-launchstatus.log``.

Once ``machine launch script completed`` appears in ``/tmp/machine-launchstatus``, exit
and re-``ssh`` into the system. If you want to use ``mosh``, ``mosh`` back into the
system.

Key Setup, Part 2
~~~~~~~~~~~~~~~~~

Now that our manager instance is started, copy the private key that you downloaded from
AWS earlier (``firesim.pem``) to ``~/firesim.pem`` on your manager instance. This step
is required to give the manager access to the instances it launches for you.
