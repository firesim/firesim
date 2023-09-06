Configuring Required Infrastructure in Your AWS Account
===========================================================

Once we have an AWS Account setup, we need to perform some advance setup
of resources on AWS. You will need to follow these steps even if you
already had an AWS account as these are FireSim-specific.

Select a region
~~~~~~~~~~~~~~~

Head to the `EC2 Management
Console <https://console.aws.amazon.com/ec2/v2/home>`__. In the top
right corner, ensure that the correct region is selected. You should
select one of: ``us-east-1`` (N. Virginia), ``us-west-2`` (Oregon), or ``eu-west-1``
(Ireland), since F1 instances are only available in those regions.

Once you select a region, it's useful to bookmark the link to the EC2
console, so that you're always sent to the console for the correct
region.

Key Setup
~~~~~~~~~

In order to enable automation, you will need to create a key named
``firesim``, which we will use to launch all instances (Manager
Instance, Build Farm, Run Farm).

To do so, click "Key Pairs" under "Network & Security" in the
left-sidebar. Follow the prompts, name the key ``firesim``, and save the
private key locally as ``firesim.pem``. You can use this key to access
all instances from your local machine. We will copy this file to our
manager instance later, so that the manager can also use it.

Double Check your EC2 Instance Limits
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

AWS limits access to particular instance types for new/infrequently used
accounts to protect their infrastructure. You can learn more about how
these limits/quotas work `here <https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-on-demand-instances.html#ec2-on-demand-instances-limits>`__.

You should make sure that your
account has the ability to launch a sufficient number of instances to follow
this guide by looking at the "Service Quotas" page in the AWS Console, which you can access
`here <https://console.aws.amazon.com/servicequotas/home/services/ec2/quotas/>`__.
Be sure that the correct region is selected once you open this page. 

The values listed on this page represent the maximum number vCPUs of any of these
instances that you can run at once, which will limit the size of
simulations (e.g., number of parallel FPGAs) that you can run. If you need to
increase your limits, follow the instructions below.

To complete this guide, you need to have the following limits:

* ``Running On-Demand F instances``: 64 vCPUs.

    * This is sufficient for 8 parallel FPGAs. Each 8 vCPUs = one FPGA.

* ``Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances``: 24 vCPUs.

    * This is sufficient for one ``c5.4xlarge`` manager instance and one ``z1d.2xlarge`` build farm instance.

If you have insufficient limits, follow the instructions on the :ref:`limitincrease` page.



Start a t2.nano instance to run the remaining configuration commands
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

To avoid having to deal with the messy process of installing packages on
your local machine, we will spin up a very cheap ``t2.nano`` instance to
run a series of one-time aws configuration commands to setup our AWS
account for FireSim. At the end of these instructions, we'll terminate
the ``t2.nano`` instance. If you happen to already have ``boto3`` and
the AWS CLI installed on your local machine, you can do this locally.

Launch a ``t2.nano`` by following these instructions:

1. Go to the `EC2 Management
   Console <https://console.aws.amazon.com/ec2/v2/home>`__ and click
   "Launch Instance"
2. In "Application and OS Images (Amazon Machine Image)", use "Amazon Linux", which should be the default.
3. In "Instance type", select ``t2.nano``.
4. In "Key pair (login)", choose the ``firesim`` key pair we created previously.
5. Click "Launch Instance" in the right-hand sidebar (we don't need to change any other
   settings)
6. Click on the instance ID and note the instance's public IP address.

.. _run-scripts-t2:

Run scripts from the t2.nano
~~~~~~~~~~~~~~~~~~~~~~~~~~~~

SSH into the ``t2.nano`` like so:

.. code-block:: bash

    ssh -i firesim.pem ec2-user@INSTANCE_PUBLIC_IP

Which should present you with something like:

.. code-block:: text

       ,     #_
       ~\_  ####_        Amazon Linux 2023
      ~~  \_#####\
      ~~     \###|
      ~~       \#/ ___   https://aws.amazon.com/linux/amazon-linux-2023
       ~~       V~' '->
        ~~~         /
          ~~._.   _/
             _/ _/
           _/m/'
    [ec2-user@ip-172-31-85-76 ~]$

On this machine, run the following:

.. code-block:: bash

    aws configure
    [follow prompts]

Within the prompt, you should specify the same region that you chose
above (one of ``us-east-1``, ``us-west-2``, ``eu-west-1``) and set the default
output format to ``json``. You will need to generate an AWS access key in the "Security Credentials" menu of your AWS settings (as instructed in https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_access-keys.html#Using_CreateAccessKey ). You should keep the AWS access key information in a safe place, so that you can refer to it again when setting up the manager instance. You can learn more about the ``aws configure`` command on the following page: https://docs.aws.amazon.com/cli/latest/reference/configure/index.html

Again on the ``t2.nano`` instance, do the following:

.. code-block:: bash
   :substitutions:

    sudo yum install -y python3-pip
    sudo python3 -m pip install boto3
    sudo python3 -m pip install --upgrade awscli
    wget https://raw.githubusercontent.com/firesim/firesim/|overall_version|/deploy/awstools/aws_setup.py
    chmod +x aws_setup.py
    ./aws_setup.py

The final command should print the following:

.. code-block:: text

    Creating VPC for FireSim...
    Success!
    Creating a subnet in the VPC for each availability zone...
    Success!
    Creating a security group for FireSim...
    Success!

This will have created a VPC named ``firesim`` and a security group named
``firesim`` in your account.

Terminate the t2.nano
~~~~~~~~~~~~~~~~~~~~~

At this point, we are finished with the general account configuration.
You should terminate the t2.nano instance you created, since we do not
need it anymore (and it shouldn't contain any important data).

.. _ami-subscription:

Subscribe to the AWS FPGA Developer AMI
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Go to the `AWS Marketplace page for the FPGA Developer
AMI <https://aws.amazon.com/marketplace/pp/B06VVYBLZZ>`__. Click the
button to subscribe to the FPGA Dev AMI (it should be free) and follow
the prompts to accept the EULA (but do not launch any instances).

Now, hit next to continue on to setting up our Manager Instance.
