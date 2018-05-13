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
getting access to the AWS credit pool. See the next section about getting
access to the research credit pool. Otherwise, skip to the following section.

Requesting Limit Increases
--------------------------

New AWS accounts generally do not have access to EC2 F1 instances by
default. In order to get access, you should file a limit increase
request.

Follow these steps to do so:

https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-resource-limits.html

You'll probably want to start out with the following requests:

Request 1:

::

    Region:                US East (Northern Virginia)
    Primary Instance Type: f1.2xlarge
    Limit:                 Instance Limit
    New limit value:       1

Request 2:

::

    Region:                US East (Northern Virginia)
    Primary Instance Type: f1.16xlarge
    Limit:                 Instance Limit
    New limit value:       1

This allows you to run 1 or 4 nodes on the ``f1.2xlarge`` or 8 or 32
nodes on the ``f1.16xlarge``.

For the "Use Case Description", you should write something about
hardware simulation and mention that information about the tool you're
using can be found at:
https://aws.amazon.com/blogs/compute/bringing-datacenter-scale-hardware-software-co-design-to-the-cloud-with-firesim-and-amazon-ec2-f1-instances/.

This process has a human in the loop, so you should submit it ASAP. At
this point, you should wait for the response to this request, as well as
the email stating that your account has been added to the RISE billing
pool, if you're a Berkeley user.

Hit Next below to continue.
