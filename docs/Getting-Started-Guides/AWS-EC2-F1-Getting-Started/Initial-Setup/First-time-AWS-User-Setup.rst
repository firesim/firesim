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

.. _limitincrease:

Requesting Limit Increases
--------------------------

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

If you have insufficient limits, request a limit increase by following these steps:
https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-resource-limits.html#request-increase

In your request, enter the vCPU limits for the two instance classes shown above.
This process sometimes has a human in the loop, so you should submit it ASAP. At
this point, you should wait for the response to this request.

Hit Next below to continue.
