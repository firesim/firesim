Running Test Software
=====================

To test our input stream device, we want to write an application that uses
the device to write data into memory, then reads the data and prints it out.

In project-template, test software is placed in the ``tests/`` directory,
which includes a Makefile and library code for developing a baremetal program.
We'll create a new file at ``tests/input-stream.c`` with the following code:

.. code-block:: c

    #include <stdio.h>
    #include <stdlib.h>
    #include <stdint.h>

    #include "mmio.h"

    #define N 4
    #define INPUTSTREAM_BASE 0x10017000L
    #define INPUTSTREAM_ADDR     (INPUTSTREAM_BASE + 0x00)
    #define INPUTSTREAM_LEN      (INPUTSTREAM_BASE + 0x08)
    #define INPUTSTREAM_RUNNING  (INPUTSTREAM_BASE + 0x10)
    #define INPUTSTREAM_COMPLETE (INPUTSTREAM_BASE + 0x18)

    uint64_t values[N];

    int main(void)
    {
            reg_write64(INPUTSTREAM_ADDR, (uint64_t) values);
            reg_write64(INPUTSTREAM_LEN, N * sizeof(uint64_t));
            asm volatile ("fence");
            reg_write64(INPUTSTREAM_RUNNING, 1);

            while (reg_read64(INPUTSTREAM_COMPLETE) == 0) {}
            reg_write64(INPUTSTREAM_COMPLETE, 0);

            for (int i = 0; i < N; i++)
                    printf("%016lx\n", values[i]);

            return 0;
    }

This program statically allocates an array for the data to be written to.
It then sets the ``addr`` and ``len`` registers, executes a ``fence``
instruction to make sure they are committed, and then sets the ``running``
register. It then continuously polls the ``complete`` register until it sees
a non-zero value, at which point it knows the data has been written to memory
and is safe to read back.

To compile this program, add "input-stream" to the ``PROGRAMS`` list in
``tests/Makefile`` and run ``make`` from the tests directory.

To run the program, return to the ``vsim/`` directory and run the simulator
executable, passing the newly compiled ``input-stream.riscv`` executable
as an argument.

.. code-block:: shell

    $ cd vsim
    $ ./simv-example-FixedInputStreamConfig ../tests/input-stream.riscv

The program should print out 

.. code-block:: text

    000000001002abcd
    0000000034510204
    0000000010329999
    0000000092101222

For verilator, the command is the following:

.. code-block:: shell

    $ cd verisim
    $ ./simulator-example-FixedInputStreamConfig ../tests/input-stream.riscv

.. _target-level-simulation:

Debugging Verilog Simulation
----------------------------

If there is a bug in your hardware, one way to diagnose the issue is to
generate a waveform from the simulation so that you can introspect into the
design and see what values signals take over time.

In VCS, you can accomplish this with the ``+vcdplusfile`` flag, which will
generate a VPD file that can be viewed in DVE. To use this flag, you will
need to build the debug version of the simulator executable.

.. code-block:: shell

    $ cd vsim
    $ make CONFIG=FixedInputStreamConfig debug
    $ ./simv-example-FixedInputStreamConfig-debug +max-cycles=50000 +vcdplusfile=input-stream.vpd ../tests/input-stream.riscv
    $ dve -full64 -vpd input-stream.vpd

The ``+max-cycles`` flag is used to set a timeout for the simulation. This is
useful in the case the program hangs without completing.

If you are using verilator, you can generate a VCD file that can be viewed in
an open source waveform viewer like GTKwave.

.. code-block:: shell

    $ cd verisim
    $ make CONFIG=FixedInputStreamConfig debug
    $ ./simulator-example-FixedInputStreamConfig-debug +max-cycles=50000 -vinput-stream.vcd ../tests/input-stream.riscv
    $ gtkwave -o input-stream.vcd
