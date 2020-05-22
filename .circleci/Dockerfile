# Docker images hosted at firesim/firesim-ci
FROM centos:centos7.6.1810

RUN yum -y install sudo epel-release
# aws fpga dev ami comes with this: pip2
RUN yum -y install python-pip

RUN adduser centos
RUN usermod -aG wheel centos
RUN echo "centos ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers
