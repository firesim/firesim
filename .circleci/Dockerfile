# Docker images hosted at firesim/firesim-ci
FROM centos:centos7.6.1810

RUN yum -y install sudo epel-release
# aws fpga dev ami comes with this: pip2
RUN yum -y install python-pip
# Provide a baseline of version for circle CI to use.
# If Circle CI uses its native version to initialize the repo, future submodule
# initializations with the machine-launch installed version of git produce very
# non-intuitive results
# (the chipyard submodule is initialized to an apparently random commit)
# If we want to get rid of this we could reclone the repo under the updated git
RUN yum -y install git

RUN adduser centos
RUN usermod -aG wheel centos
RUN echo "centos ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers
