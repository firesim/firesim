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

Check your EC2 Instance Limits
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

AWS limits access to particular instance types for new/infrequently used
accounts to protect their infrastructure. You should make sure that your
account has access to ``f1.2xlarge``, ``f1.4xlarge``, ``f1.16xlarge``,
``m4.16xlarge``, and ``c4.4xlarge`` instances by looking at the "Limits" page
in the EC2 panel, which you can access
`here <https://console.aws.amazon.com/ec2/v2/home#Limits:>`__. The
values listed on this page represent the maximum number of any of these
instances that you can run at once, which will limit the size of
simulations (# of nodes) that you can run. If you need to increase your
limits, follow the instructions on the
:ref:`limitincrease` page.
To follow this guide, you need to be able to run one ``f1.2xlarge`` instance
and two ``c4.4xlarge`` instances.

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
2. On the AMI selection page, select "Amazon Linux AMI...", which should
   be the top option.
3. On the Choose an Instance Type page, select ``t2.nano``.
4. Click "Review and Launch" (we don't need to change any other
   settings)
5. On the review page, click "Launch"
6. Select the ``firesim`` key pair we created previously, then click
   Launch Instances.
7. Click on the instance name and note its public IP address.

Run scripts from the t2.nano
~~~~~~~~~~~~~~~~~~~~~~~~~~~~

SSH into the ``t2.nano`` like so:

::

    ssh -i firesim.pem ec2-user@INSTANCE_PUBLIC_IP

Which should present you with something like:

::

    Last login: Mon Feb 12 21:11:27 2018 from 136.152.143.34

           __|  __|_  )
           _|  (     /   Amazon Linux AMI
          ___|\___|___|

    https://aws.amazon.com/amazon-linux-ami/2017.09-release-notes/
    4 package(s) needed for security, out of 5 available
    Run "sudo yum update" to apply all updates.
    [ec2-user@ip-172-30-2-66 ~]$

On this machine, run the following:

::

    aws configure
    [follow prompts]

See
https://docs.aws.amazon.com/cli/latest/userguide/tutorial-ec2-ubuntu.html#configure-cli-launch-ec2
for more about aws configure. You should specify the same region that you chose
above (one of ``us-east-1``, ``us-west-2``, ``eu-west-1``) and set the default
output format to ``json``.

Again on the ``t2.nano`` instance, do the following:

::

    sudo yum -y install python-pip
    sudo pip install boto3
    wget https://raw.githubusercontent.com/firesim/firesim/master/scripts/aws-setup.py
    python aws-setup.py

This will create a VPC named ``firesim`` and a security group named
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
