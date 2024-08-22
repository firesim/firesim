Adding support for a new FPGA
=============================

To use FireSim, an FPGA, at minimum, needs to provide an MMIO (i.e. 32b AXI4-Lite)
interface that can interact with a C++ driver. This MMIO interface is used to coordinate
the simulation. However, for all FireSim features, an FPGA needs to also provide a DMA
(i.e. 512b AXI4) interface that can interact with a C++ driver and DRAM (also AXI4).
While optional, DMA and DRAM provide extra features of FireSim such as TracerV and DRAM
for the FASED DDR memory model.

In the case of Xilinx FPGAs, both the MMIO and DMA interface are provided by XDMA. XDMA
allows a C++ driver to send data to/from the FPGA.

The following sections talk about the different changes needed to support a new FPGA
using the Xilinx Alveo U250 as an example. When creating new folders and files, rename
``xilinx_alveo_u250`` to your specific FPGA name. We will use this name across various
files as the "platform" name. For each of these sections, feel free to copy/modify them
to your new FPGA.

.. note::

    FPGAs in FireSim, when first developed, start with implementing/testing the
    AXI4-Lite interface before moving to add the DMA interface and DRAM. We highly
    recommend you to follow the same flow when adding an FPGA.

.. warning::

    This documentation is new. Feel free to make any PRs or give any feedback to make
    this easier to read and understand. Additionally, if a new FPGA works, feel free to
    PR it to the FireSim mainline repo. Thanks!

Adding a new FireSim ``platform``
---------------------------------

FireSim first needs to understand how to build a bitstream for the FPGA wanted using a
synthesis tool like Vivado. This code is held in :gh-file-ref:`platforms`.

Let's take a look at :gh-file-ref:`platforms/xilinx_alveo_u250`. At the top-level is
:gh-file-ref:`platforms/xilinx_alveo_u250/build-bitstream.sh` which invokes Vivado in a
specific directory called ``CL_DIR`` (custom logic directory) to build the bitstream
wanted. This serves as the interface for the FireSim manager to modify a bitstream build
(i.e. change the frequency of a design, pass synthesis options from the YAML, etc).

:gh-file-ref:`platforms/xilinx_alveo_u250/cl_firesim` holds all RTL, TCL, and more
needed to build a bitstream for a specific FPGA. Typically, during ``firesim
buildbitstream``, FireSim's build system copies this folder to a new location on the
manager machine with a unique name (i.e. the configuration quintuplet), adds the RTL
generated from Golden Gate (i.e. ``FireSim-generated.sv`` specifying the top-level
module), then copies it to the build farm machine/instance. Then the manager copies the
:gh-file-ref:`platforms/xilinx_alveo_u250/build-bitstream.sh` to the build farm
machine/instance and calls it with the path to the build farm machine/instance
``CL_DIR``.

Within :gh-file-ref:`platforms/xilinx_alveo_u250/cl_firesim` you'll see an RTL top-level
file that instantiates the top-level FireSim module called ``F1Shim`` (in this case the
name ``F1Shim`` is because this top-level module is virtually the same as the AWS
equivalent) and connects both 32b/512b AXI4 buses from the FPGA top-level to the FireSim
module. An MMIO-only (32b AXI4-Lite-only) design can just tieoff the DMA interface and
DRAM to begin with.

The various scripts in the :gh-file-ref:`platforms/xilinx_alveo_u250` area are for
flashing the FPGA, building the bitstream, etc.

Most likely you can copy this platform and modify it to your new FPGA if you are running
an XDMA-enabled FPGA.

.. note::

    Unlike the AWS version of the equivalent platform, the Xilinx Alveo U250 platform
    creates a full bitstream that flashes the entire FPGA. The scripts that reside in
    the Xilinx Alveo U250 platform also work around an issue related to this where you
    need PCIe to always be functioning (even when flashing the FPGA). The AWS equivalent
    doesn't need this extra scripting since it uses a shell to selectively program all
    parts that aren't associated with PCIe, resulting in the PCIe link always being up.

Adding other collateral (Scala, C++, Make, etc)
-----------------------------------------------

Next, you'll need to tell the FireSim build system (i.e. Make) how to build the
top-level FireSim module copied into the ``CL_DIR`` mentioned above. Additionally,
you'll also tell FireSim how to build a corresponding C++ driver for simulation.

First, you'll need to add new Scala configurations to tell Golden Gate there is a new
FPGA. This can be done in :gh-file-ref:`sim/midas/src/main/scala/midas/Config.scala` and
:gh-file-ref:`sim/midas/src/main/scala/configs/CompilerConfigs.scala`. These configs
indicate what the FireSim top-level is (i.e. ``F1Shim``), what its AXI4 parameters are
for ports, what ports are available (of what type), what DRAM is available, etc.

Next, you'll need to provide a C++ interface that allows FireSim to read/write to the
FPGA's MMIO (AXI4-Lite) and DMA (AXI4) port through XDMA. An example of doing this is
:gh-file-ref:`sim/midas/src/main/cc/simif_xilinx_alveo_u250.cc`. This file implements
functions like ``fpga_setup``, ``read``, ``write``, ``cpu_managed_axi4_read``, and
``cpu_managed_axi4_write`` which correspond to setting up the XDMA interfaces, and MMIO
and DMA read/writes.

Next, you'll need to add a hook to FireSim's make system to build the FPGA RTL and also
build the C++ driver with the given ``simif_*`` file. This is done in
:gh-file-ref:`sim/make/fpga.mk` and :gh-file-ref:`sim/make/driver.mk`. For most cases,
you can copy/paste and follow along with the ``xilinx_alveo_u250`` example (if you are
building a driver that only depends on Conda and doesn't depend on system C++
libraries).

At this point you should be able to build the RTL using something like ``make -C sim
PLATFORM=xilinx_alveo_u250 xilinx_alveo_u250`` where you can replace
``xilinx_alveo_u250`` with your FPGA platform name. This should build both the C++
driver and the RTL associated with it that is copied for synthesis.

Manager build modifications
---------------------------

Next, you'll need to tell the FireSim manager a new platform exists to use it in
``firesim buildbitstream``.

First, we need to add a "bit builder" class that gives the Python code necessary to
build and synthesize the RTL on a build farm instance/machine and copy the results back
into a FireSim HWDB entry. This code is located in
:gh-file-ref:`deploy/buildtools/bitbuilder.py`. In summary, this class should implement
the ``build_bitstream``, and ``setup`` methods from ``BitBuilder``. In the Xilinx Alveo
U250 case, the ``build_bitstream`` function builds a bitstream by doing the following in
Python:

1. Creates a copy of the ``platform`` area previously described on the build farm
   machine/instance
2. Adds the RTL built with the ``make`` command from the prior section to that copied
   area (i.e. ``CL_DIR``)
3. Runs the :gh-file-ref:`platforms/xilinx_alveo_u250/build-bitstream.sh` script with
   the copied area.
4. Retrieves the bitstream built and compiles a ``*.tar.gz`` file with it. Uses that
   file in a HWDB entry.

Next, since this class can take arguments from FireSim's YAML, you'll need to add a YAML
file for a new FPGA in :gh-file-ref:`deploy/bit-builder-recipes` (even if it has no
args).

Now you can build a bitstream using the FireSim manager by changing the build recipe
arguments (i.e. ``PLATFORM``, ``PLATFORM_CONFIG``, ``bit_builder_recipe``). For example,
here is a ``xilinx_alveo_u250`` recipe:

.. code-block:: yaml

    custom_recipe:
        ...
        PLATFORM: xilinx_alveo_u250 # platform name
        PLATFORM_CONFIG: BaseXilinxAlveoU250Config # config added for platform
        bit_builder_recipe: bit-builder-recipes/xilinx_alveo_u250.yaml # extra yaml file given earlier
        ...

Manager run modifications
-------------------------

Next, you'll need to tell the FireSim manager a new platform exists to use it run
simulation commands like ``firesim runworkload``.

First, for convenience, you'll need to indicate a new platform exists by adding it in
:gh-file-ref:`deploy/firesim`. This modifies the YAML files when running ``firesim
managerinit`` to have the right values initially.

Finally, you'll need to add an "instance deploy manager" to tell the FireSim manager how
to flash an FPGA, start a simulation, and more. This is seen in
:gh-file-ref:`deploy/runtools/run_farm_deploy_managers.py`. Typically, FPGAs need to
implement the ``infrasetup_instance`` method of ``InstanceDeployManager`` telling a run
farm machine how to flash an FPGA. These Python steps create a simulation directory on
the run farm machine/instance, copy simulation collateral to it (including the
bitstream), and flash the FPGA.

Now you should be able to run a simulation with the given bitstream by pointing to your
specific instance deploy manager and bitstream that was built. For example, in the
Xilinx Alveo U250 case, in the ``config_runtime.yaml`` you can modify the
``default_platform`` to be ``XilinxAlveoU250InstanceDeployManager`` and change the HWDB
entry to be the recipe built for the new FPGA.
