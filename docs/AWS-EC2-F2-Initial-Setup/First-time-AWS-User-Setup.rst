.. _first-time-aws:

First-time AWS User Setup
=========================

If you've never used AWS before and don't have an account, follow the instructions below
to get started.

Creating an AWS Account
-----------------------

First, you'll need an AWS account. Create one by going to `aws.amazon.com
<https://aws.amazon.com>`__ and clicking "Sign Up." You'll want to create a personal
account. You will have to give it a credit card number.

.. _limitincrease:

Requesting Limit Increases
--------------------------

AWS limits access to particular instance types for new/infrequently used accounts to
protect their infrastructure. You can learn more about how these limits/quotas work
`here
<https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-on-demand-instances.html#ec2-on-demand-instances-limits>`__.

Select one of the following regions, which has F2 support, according to your location:

- US East (N. Virginia) [``us-east-1``]
- US West (Oregon) [``us-west-2``]
- EU (London) [``eu-west-2``]

You should make sure that your account has the ability to launch a sufficient number of
instances to follow this guide by looking at the "Service Quotas" page in the AWS
Console, which you can access `here
<https://console.aws.amazon.com/servicequotas/home/services/ec2/quotas/>`__. Be sure
that the correct region is selected once you open this page.

The values listed on this page represent the maximum number vCPUs of any of these
instances that you can run at once, which will limit the size of simulations (e.g.,
number of parallel FPGAs) that you can run. If you need to increase your limits, follow
the instructions below.

.. note::

    Note that requests will take a few business days to process by Amazon AWS (prepare
    for a worst case time of 1 week if cases need to be escalated).

We recommend the following limits:

- ``Running On-Demand F instances``: 1024 vCPUs.

      - This is sufficient for 42 parallel FPGAs. Each 24 vCPUs = one FPGA.

- ``Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances``: 1024 vCPUs.

      - We will use ``c5.4xlarge`` for our FireSim manager instance and ``z1d.2xlarge``
        for our build farm instances.
      - Each ``c5.4xlarge`` requires 10 vCPUs
      - Each ``z1d.2xlarge`` requires 8 vCPUs
      - Your 1024 vCPU capacity is shared across the ``A, C, D, H, I, M, R, T, Z``
        instances you launch.

At a minimum, you need to have the following limits:

- ``Running On-Demand F instances``: 192 vCPUs.

      - This is sufficient for 8 parallel FPGAs. Each 24 vCPUs = one FPGA.

- ``Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances``: 24 vCPUs.

      - This is sufficient for one ``c5.4xlarge`` manager instance and one
        ``z1d.2xlarge`` build farm instance.

If you have insufficient limits, request a limit increase by following these steps:

Visit:
https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-resource-limits.html#request-increase

In your request, enter the vCPU limits for the two instance classes shown above.

At this point, you should wait for the response to this request.

If your request gets rejected, you will need to go through the escalation process.
Gather the following details from your request:

- Your Support Case ID for your rejected request (or both if both requests were
  rejected). You can find your case ID from the `AWS Support page
  <https://support.console.aws.amazon.com/support/home#/case/history>`__, under `Support
  cases`.
- Your `Service quota` string that you requested a quota increase on. Ex: `Running
  On-Demand F instances`.
- The number of vCPUs you requested a quota increase to.
- Requested region. Ex: `US West (Oregon)`.

You will then need to send an email to Amazon AWS for escalation. 
We've been informed by AWS that if you are not able to be granted a quota increase through their normal quota increase process, you will need to reach out to AWS Support or your site/institution's AWS Sales Support Representative (if applicable) with the case details specified above. 
They will handle case-by-case escalations to increase your quota.
AWS has given us the following 2 links to start from:

- https://aws.amazon.com/contact-us/?nc2=h_ut_cu
- Fill out the "Request AWS sales support" form located here: https://aws.amazon.com/contact-us/sales-support/

Hit Next below to continue.
