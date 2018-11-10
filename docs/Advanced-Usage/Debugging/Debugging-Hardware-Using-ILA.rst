Debugging Using FPGA Integrated Logic Analyzers (ILA)
=====================================================

Sometimes it takes too long to simulate FireSim on RTL simulators, and 
in some occasions we would also like to debug the simulation infrastructure
itself. For these purposes, we can use the Xilinx Integrated Logic Analyzer
resources on the FPGA. 

ILAs allows real time sampling of pre-selected signals during FPGA runtime, 
and provided and interface for setting trigger and viewing samples waveforms
from the FPGA. For more information about ILAs, please refer to the Xilinx
guide on the topic

Midas provides custom Chisel annotations which allow annotating signals in the
Chisel source code, which will automatically generate custom ILA IP for the
fpga, and then transforme and wire the relevant signals to the ILA.

ILAs consume FPGA resources, and therefore it is recommended not to annotate a
large number of signals.

Annotating Signals
------------------------

In order to annotate a signal, we must import ``midas.passes.FpgaDebugAnnotation``.
We then simply add a relevant ``FpgaDebugAnnotation(<selected_signal>)`` with the
desired signal as an argument.

Example:

::

    import midas.passes.FpgaDebugAnnotation

    class SomeModuleIO(implicit p: Parameters) extends SomeIO()(p){
       val out1 = Output(Bool())
       val in1 = Input(Bool())
       chisel3.experimental.annotate(FpgaDebugAnnotation(out1))
    }

Note: In case the module with the annotated signal is instantiated multiple times,
all instatiations of the annotated signal will be wired to the ILA.



Using the ILA at Runtime
------------------------

Prerequisite: Make sure that ports 3121 and 10201 are enabled in the firesim AWS security group.

In order to use the ILA, we must enable the GUI interface on our manager instance.
This can be done by running the command:

::

  /home/centos/src/scripts/setup_gui.sh

When the command will finish running, a temporary password will be printed out. This
password will be used to access the GUI interface of the master instance. We will
connect to the GUI interface of the manager instance using an RDP client. Use the
public IP address of the manager instances in order to connect using the RDP client.
The username is `centos`, and the password is the temporary password that was printed
out at the end of the previous command. An additional login screen with the username
Cloud-User and the same password may appear in some occasion. More information about
the AWS GUI interface can be found in the ``~/src/GUI_README`` on the manager instance.

After access the GUI interface, open a terminal, and open ``vivado``.
Follow the instructions in the `AWS-FPGA guide for connecting xilinx hardware manager on vivado (running on a remote machine) to the debug target  <https://github.com/aws/aws-fpga/blob/master/hdk/docs/Virtual_JTAG_XVC.md#connecting-xilinx-hardware-manager-vivado-lab-edition-running-on-a-remote-machine-to-the-debug-target-fpga-enabled-ec2-instance>`__ .

where ``<hostname or IP address>`` is the internal IP of the simulation instance (not
the manager instance. i.e. The IP starting with 192.168.X.X).
The probes file can be found in the manager instance under the path 
``firesim/deploy/results-build/<build_identifier>/cl_firesim/build/checkpoints/<probes_file.ltx>``

Select the ILA with the description of `WRAPPER_INST/CL/CL_FIRESIM_DEBUG_WIRING_TRANSFORM`, and you may now use the ILA just as if it was on
a local FPGA.

