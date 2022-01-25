.. _auto-ila:

AutoILA: Simple Integrated Logic Analyzer (ILA) Insertion
===================================================================

Sometimes it takes too long to simulate FireSim on RTL simulators, and
in some occasions we would also like to debug the simulation infrastructure
itself. For these purposes, we can use the Xilinx Integrated Logic Analyzer
resources on the FPGA.

ILAs allows real time sampling of pre-selected signals during FPGA runtime,
and provided and interface for setting trigger and viewing samples waveforms
from the FPGA. For more information about ILAs, please refer to the Xilinx
guide on the topic.

The ``midas.targetutils`` package provides annotations for labeling
signals directly in the Chisel source. These will be consumed by a downstream
FIRRTL pass which wires out the annotated signals, and binds them to an
appropriately sized ILA instance.

Enabling AutoILA
----------------

To enable AutoILA, mixin `WithAutoILA` must be prepended to the
`PLATFORM_CONFIG`. Prior to version 1.13, this was done by default.

Annotating Signals
------------------------

In order to annotate a signal, we must import the
``midas.targetutils.FpgaDebug`` annotator. FpgaDebug's apply method accepts a
vararg of chisel3.Data. Invoke it as follows:

::

    import midas.targetutils.FpgaDebug

    class SomeModuleIO(implicit p: Parameters) extends SomeIO()(p){
       val out1 = Output(Bool())
       val in1 = Input(Bool())
       FpgaDebug(out1, in1)
    }

You can annotate signals throughout FireSim, including in Golden Gate
Rocket-Chip Chisel sources, with the only exception being the Chisel3 sources
themselves (eg. in Chisel3.util.Queue).

Note: In case the module with the annotated signal is instantiated multiple times,
all instatiations of the annotated signal will be wired to the ILA.

Setting a ILA Depth
-------------------

The ILA depth parameter specifies the duration in cycles to capture annotated signals
around a trigger. Increasing this parameter may ease debugging, but will also increase
FPGA resource utilization. The default depth is 1024 cycles. The desired depth can be
configured much like the desired HostFrequency by appending a mixin to the
`PLATFORM_CONFIG`. See :ref:`Generating-Different-Targets` for details on `PLATFORM_CONFIG`.

Below is an example `PLATFORM_CONFIG` that can be used in the `build_recipes` config file.

::

   PLATFORM_CONFIG=ILADepth8192_BaseF1Config



Using the ILA at Runtime
------------------------

Prerequisite: Make sure that ports 8443, 3121 and 10201 are enabled in the "firesim" AWS security group.

In order to use the ILA, we must enable the GUI interface on our manager instance.
In the past, AWS had a custom ``setup_gui.sh`` script. However, this was recently deprecated due to compatibility
issues with various packages. Therefore, AWS currently recommends using `NICE DCV <https://docs.aws.amazon.com/dcv/latest/adminguide/what-is-dcv.html>`__ as a GUI client. You should `download a DCV client <https://docs.aws.amazon.com/dcv/latest/userguide/client.html>`__, and then run the following commands on your FireSim manager instance:

::

  sudo yum -y groupinstall "GNOME Desktop"
  sudo yum -y install glx-utils
  sudo rpm --import https://s3-eu-west-1.amazonaws.com/nice-dcv-publish/NICE-GPG-KEY
  wget https://d1uj6qtbmh3dt5.cloudfront.net/2019.0/Servers/nice-dcv-2019.0-7318-el7.tgz
  tar xvf nice-dcv-2019.0-7318-el7.tgz
  cd nice-dcv-2019.0-7318-el7
  sudo yum -y install nice-dcv-server-2019.0.7318-1.el7.x86_64.rpm
  sudo yum -y install nice-xdcv-2019.0.224-1.el7.x86_64.rpm
  sudo systemctl enable dcvserver
  sudo systemctl start dcvserver
  sudo passwd centos
  sudo systemctl stop firewalld
  dcv create-session --type virtual --user centos centos

These commands will setup Linux desktop pre-requisites, install the NICE DCV server, ask you to setup the password to the ``centos`` user, disable firewalld,
and finally create a DCV session. You can now connect to this session through the DCV client.

After access the GUI interface, open a terminal, and open ``vivado``.
Follow the instructions in the `AWS-FPGA guide for connecting xilinx hardware manager on vivado (running on a remote machine) to the debug target  <https://github.com/aws/aws-fpga/blob/master/hdk/docs/Virtual_JTAG_XVC.md#connecting-xilinx-hardware-manager-vivado-lab-edition-running-on-a-remote-machine-to-the-debug-target-fpga-enabled-ec2-instance>`__ .

where ``<hostname or IP address>`` is the internal IP of the simulation instance (not
the manager instance. i.e. The IP starting with 192.168.X.X).
The probes file can be found in the manager instance under the path
``firesim/deploy/results-build/<build_identifier>/cl_firesim/build/checkpoints/<probes_file.ltx>``

Select the ILA with the description of `WRAPPER_INST/CL/CL_FIRESIM_DEBUG_WIRING_TRANSFORM`, and you may now use the ILA just as if it was on
a local FPGA.
