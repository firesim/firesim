Getting Started
===============

In this tutorial, we will show you how to design a new memory-mapped IO
device, test it in simulation, and then build and run it on FireSim.

To start with, you will need to clone a copy of FireChip, the repository
that aggregates all the target RTL for FireSim. FireSim already contains
FireChip as a submodule under ``target-design/firechip``, but it makes patches
to the codebase so that it will work with the FPGA tools. Therefore, you will
need to clone a clean copy if you want to use FireChip standalone.

Go to https://github.com/firesim/firechip and click the "Fork" button to
fork the repository to your own account. Now clone the new repo to your
local machine and initialize the submodules.

.. code-block:: shell

    $ git clone https://github.com/yourusername/firechip.git
    $ cd firechip
    $ git submodule update --init
    $ cd rocket-chip
    $ git submodule update --init
    $ cd ..

You will not need to install the riscv-tools again because you'll just be
reusing the one in firesim. So make sure to go into firesim and source
``sourceme-f1-full.sh`` before you run the rest of the commands in this
tutorial.

Now that everything is checked out, you can build the VCS simulator and run the
regression tests to make sure everything is working.

.. code-block:: shell

    $ cd vsim # or "cd verisim" for verilator
    $ make # builds the DefaultExampleConfig
    $ make run-regression-tests

If everything is set up correctly, you should see a bunch of ``*.out`` files
in the ``output/`` directory. If you open these up, they should all say
"Completed after XXXXX cycles" at the end and not have any error messages.
