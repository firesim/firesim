FPGA Dev AMI Remote Desktop Setup
===========================

To Remote Desktop into your manager instance, you must do the following:

::

    curl https://s3.amazonaws.com/aws-fpga-developer-ami/1.4.0/Scripts/setup_gui.sh -o /home/centos/src/scripts/setup_gui.sh
    sudo sed -i 's/enabled=0/enabled=1/g' /etc/yum.repos.d/CentOS-CR.repo
    /home/centos/src/scripts/setup_gui.sh

The former two commands are required due to AWS FPGA Dev AMI 1.3.5 incompatibilities. See

https://forums.aws.amazon.com/message.jspa?messageID=848073#848073

and

https://forums.aws.amazon.com/ann.jspa?annID=5710

