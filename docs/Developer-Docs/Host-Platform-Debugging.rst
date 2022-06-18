Complete FPGA Metasimulation
=========================================

Generally speaking, users will only ever need to use conventional
metasimulation (formerly, MIDAS-level simulation). However, when bringing up a
new FPGA platform, or making changes to an existing one, doing a complete
pre-synthesis RTL simulation of the FPGA project (which we will refer to as
FPGA-level metasimulation) may be required. This will simulate the entire RTL
project passed to Vivado, and includes exact RTL models of the host memory
controllers and PCI-E subsystem used on the FPGA.  Note, since FPGA-level
metasimulation should generally not be deployed by users, when we refer to
metasimulation in absence of the FPGA-level qualifier we mean the faster form
described in :ref:`Debugging & Testing with Metasimulation<metasimulation>`

FPGA-level metasimulations run out of :gh-file-ref:`sim/`, and consist of two components:

1. A FireSim-f1 driver that talks to a simulated DUT instead of the FPGA
2. The DUT, a simulator compiled with either XSIM or VCS, that receives commands from the aforementioned
   FireSim-f1 driver

-----
Usage
-----

To run a simulation you need to make both the DUT and driver targets by typing::

    make xsim
    make xsim-dut <VCS=1> & # Launch the DUT
    make run-xsim SIM_BINARY=<PATH/TO/BINARY/FOR/TARGET/TO/RUN> # Launch the driver

When following this process, you should wait until ``make xsim-dut`` prints
``opening driver to xsim`` before running ``make run-xsim`` (getting these prints from
``make xsim-dut`` will take a while).

Once both processes are running, you should see::

    opening driver to xsim
    opening xsim to driver

This indicates that the DUT and driver are successfully communicating.
Eventually, the DUT will print a commit trace from Rocket Chip. There will
be a long pause (minutes, possibly an hour, depending on the size of the
binary) after the first 100 instructions, as the program is being loaded
into FPGA DRAM.

XSIM is used by default, and will work on EC2 instances with the FPGA developer
AMI.  If you have a license, setting ``VCS=1`` will use VCS to compile the DUT
(4x faster than XSIM). Berkeley users running on the Millennium machines should
be able to source :gh-file-ref:`scripts/setup-vcsmx-env.sh` to setup their
environment for VCS-based FPGA-level simulation.

The waveforms are dumped in the FPGA build directories (
``firesim/platforms/f1/aws-fpga/hdk/cl/developer_designs/cl_<DESIGN>-<TARGET_CONFIG>-<PLATFORM_CONFIG>``).

For XSIM::

    <BUILD_DIR>/verif/sim/vivado/test_firesim_c/tb.wdb

And for VCS::

    <BUILD_DIR>/verif/sim/vcs/test_firesim_c/test_null.vpd

When finished, be sure to kill any lingering processes if you interrupted simulation prematurely.
