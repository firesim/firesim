.. _first-time-aws:

First-time AWS User Setup
==============================

If you've never used AWS before and don't have an account, follow the instructions
below to get started.

Creating an AWS Account
-----------------------

First, you'll need an AWS account. Create one by going to
`aws.amazon.com <https://aws.amazon.com>`__ and clicking "Sign Up."
You'll want to create a personal account. You will have to give it a
credit card number.

AWS Credit at Berkeley
----------------------

If you're an internal user at Berkeley and affiliated with UCB-BAR or the RISE
Lab, see the `RISE Lab Wiki
<https://rise.cs.berkeley.edu/wiki/resources/aws>`__  for instructions on
getting access to the AWS credit pool. Otherwise, continue with the following section.

.. _limitincrease:

Requesting Limit Increases
--------------------------

In our experience, new AWS accounts do not have access to EC2 F1 instances by
default. In order to get access, you should file a limit increase
request. You can learn more about EC2 instance limits here: https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-on-demand-instances.html#ec2-on-demand-instances-limits

To request a limit increase, follow these steps:

https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-resource-limits.html

You'll probably want to start out with the following request, depending on your existing limits:

::

    Limit Type:                EC2 Instances
    Region:                    US East (Northern Virginia)
    Primary Instance Type:     All F instances
    Limit:                     Instance Limit
    New limit value:           64


This limit of 64 vCPUs for F instances allows you to run one node on the ``f1.2xlarge`` or eight nodes on the
``f1.16xlarge``.

For the "Use Case Description", you should describe your project and write
something about hardware simulation and mention that information about the tool
you're using can be found at: https://fires.im

This process has a human in the loop, so you should submit it ASAP. At
this point, you should wait for the response to this request.

If you're at Berkeley/UCB-BAR, you also need to wait until your account has
been added to the RISE billing pool, otherwise your personal CC will be charged
for AWS usage.

Hit Next below to continue.
