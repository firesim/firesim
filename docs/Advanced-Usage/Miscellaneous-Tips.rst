Miscellaneous Tips
=============================

.. _fsimcluster-aws-panel:

Add the ``fsimcluster`` column to your AWS management console
----------------------------------------------------------------

Once you've deployed a simulation once with the manager, the AWS management console
will allow you to add a custom column that will allow you to see at-a-glance
which FireSim run farm an instance belongs to.

To do so, click the gear in the top right of the AWS management console. From
there, you should see a checkbox for ``fsimcluster``. Enable it to see the column.

FPGA Dev AMI Remote Desktop Setup
-----------------------------------

To Remote Desktop into your manager instance, you must do the following:

::

    curl https://s3.amazonaws.com/aws-fpga-developer-ami/1.4.0/Scripts/setup_gui.sh -o /home/centos/src/scripts/setup_gui.sh
    sudo sed -i 's/enabled=0/enabled=1/g' /etc/yum.repos.d/CentOS-CR.repo
    /home/centos/src/scripts/setup_gui.sh

The former two commands are required due to AWS FPGA Dev AMI 1.3.5 incompatibilities. See

https://forums.aws.amazon.com/message.jspa?messageID=848073#848073

and

https://forums.aws.amazon.com/ann.jspa?annID=5710

