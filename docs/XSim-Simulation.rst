XSim Simulation
===============

Note, there is currently no XSim support for DMA\_PCIS, so you must
remove IceNIC from Generator.scala to use XSim. You can do that by
following the diff on `this
page <https://github.com/firesim/firesim/wiki/Remove-NIC-from-Generator.scala>`__.

Intro
-----

Using XSim simulation allows you to model the entire contents of the
FPGA in software simulation for development. This is particularly useful
if in a couple of cases:

1. `VCS
   simulation <https://github.com/firesim/firesim/wiki/VCS-Simulation>`__
   of the simulation is working, but running the simulator on the FPGA
   is not.
2. You've made changes to the AWS Shell/IP/cl\_firesim.sv in aws-fpga
   and want to test.

If you haven't tried `VCS
simulation <https://github.com/firesim/firesim/wiki/VCS-Simulation>`__,
you should probably try that first, as it is much faster. Expect on the
order of 1 Hz for XSim simulation (in terms of target simulation rate).

XSim simulation consists of two components:

1. A FireSim-f1 driver that talks to XSim instead of the FPGA
2. An XSim simulator that receives commands from the aforementioned
   FireSim-f1 driver

Usage
-----

First, build the appropriate version of FireSim-f1 by running:

::

    [in firesim/sim]
    make f1

Next, produce the Verilog RTL that we will use to build the XSim
simulation:

::

    [in firesim/sim]
    make replace-rtl

Run the FireSim-f1 driver we built now, for example:

::

    LD_LIBRARY_PATH=output/f1/FireSimRocketChipConfig/ ./output/f1/FireSimRocketChipConfig/FireSim-f1 +mm_readLatency=10 +mm_writeLatency=10 +mm_readMaxReqs=4 +mm_writeMaxReqs=4 ../riscv-tools-install/riscv64-unknown-elf/share/riscv-tests/isa/rv64ui-p-add

Now, we can build/run XSim. To do so, run the following:

::

    [go to firesim/platforms/f1/aws-fpga/hdk/cl/developer_designs/cl_firesim/verif/scripts]
    make C_TEST=test_firesim

This window will (probably) begin to print the Rocket Chip commit log,
as well as any other prints in your RTL.

If you return to the window in which you ran FireSim-f1, you will see
any console output your application produced. When the simulation
completes, you will also see the simulation rate printed out. For
example, when running ``rv64ui-p-add``, you should see output like:

::

    centos@ip-172-30-2-194.us-west-2.compute.internal:~/firesim/sim$ LD_LIBRARY_PATH=output/f1/FireSimRocketChipConfig/ ./output/f1/FireSimRocketChipConfig/FireSim-f1 +mm_readLatency=10 +mm_writeLatency=10 +mm_readMaxReqs=4 +mm_writeMaxReqs=4 ../riscv-tools-install/riscv64-unknown-elf/share/riscv-tests/isa/rv64ui-p-add
    opening driver to xsim
    opening xsim to driver
    random min: 0x0, random max: 0xffffffffffffffff
    time elapsed: 3473.2 s, simulation speed = 0.00 KHz
    *** PASSED *** after 3005 cycles
    Runs 3005 cycles
    [PASS] FireSim Test
    SEED: 1516922002

Viewing Waveforms
-----------------

TODO: fill in completely

Follow ~/src/GUI\_README.md

Remote desktop in

Follow instructions in aws-fpga
