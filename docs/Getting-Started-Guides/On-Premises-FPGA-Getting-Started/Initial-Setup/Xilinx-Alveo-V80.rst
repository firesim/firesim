.. |hwdb_entry_name| replace:: ``xilinx_alveo_v80_firesim_rocket_singlecore_no_nic``

FPGA Setup
==========

The following installation steps are FPGA-specific and should be run on all **run farm
machines** that install an FPGA. You will need ``sudo`` access to set up the FPGA.

Unlike the XDMA-based boards, the `Xilinx Alveo V80
<https://www.amd.com/en/products/accelerators/alveo/v80.html>`_ does not use the XDMA
driver: the FireSim driver talks to the FPGA through the Versal CPM's PCIe endpoint, by
memory-mapping its BAR. The XDMA and XVSEC drivers from :ref:`initial-local-setup` are
not needed on a machine that only has V80s.

Installing the FPGA
-------------------

1. Poweroff your machine.
2. Install the V80 and its auxiliary power cable following AMD's `Alveo V80 Installation
   Guide (UG1617) <https://docs.amd.com/r/en-US/ug1617-alveo-v80>`_.
3. Attach a JTAG cable between the FPGA and the host machine. FireSim programs the FPGA
   over JTAG before each simulation.
4. Boot the machine.

Writing the FireSim image to flash
----------------------------------

FireSim needs each V80 to boot a FireSim image from its flash, so that the FPGA shows up
as a FireSim FPGA (``10ee:903f``) and can be reprogrammed without rebooting. V80s ship
with AMD's AVED example design in flash, whose management firmware (AMC) can write the
flash over PCIe. We use it, through AMD's AVED Management Interface (AMI) tool, to save
a copy of the factory image and write the FireSim image.

First, check what each V80 is running:

.. code-block:: bash

    lspci -nn -d 10ee:

- **A FireSim image** (``Memory controller [0580]: ... [10ee:903f]``): the V80 is
  already set up for FireSim. Skip to the last step below to check it.
- **AMD's factory image** (e.g., ``Processing accelerators [1200]: ... [10ee:50b4]``):
  follow all of the steps below.
- **Anything else**, or no entry for the V80 (e.g., blank or corrupted flash, another
  design, or a flash write that was interrupted): first restore AMD's factory image over
  JTAG with AMD's `Updating FPT Image in Flash
  <https://xilinx.github.io/AVED/amd_v80_gen5x8_24.1_20241002/AVED+Updating+FPT+Image+in+Flash.html>`_
  instructions, which use the Vivado Hardware Manager and files from AMD's AVED
  deployment package. If the V80 cannot be programmed that way, first switch it to JTAG
  boot mode as described in AMD's `AVED JTAG boot recovery
  <https://xilinx.github.io/AVED/amd_v80_gen5x8_24.1_20241002/AVED+JTAG+Boot+Recovery.html>`_
  instructions. Then cold-boot and follow all of the steps below.

1. Build and load AMI by following AMD's `AVED documentation
   <https://xilinx.github.io/AVED/>`_. The AMI version must match the AMC version
   running on the card (same major and minor version), so pick the AVED release
   accordingly. Then check that AMI can talk to the card:

   .. code-block:: bash

       sudo ami_tool overview

   Each V80 should be listed with an AMC version and the state ``READY``. Note each
   V80's PCIe address (the ``BDF`` column, e.g., ``01:00.0``) for the next steps. If AMI
   reports ``NO_AMC``, check the kernel log (``sudo dmesg``) for a version mismatch.
   Older AMI releases may need small fixes to build on recent Linux kernels.

2. Obtain an existing bitstream tar file for your FPGA by opening the ``bitstream_tar``
   URL listed under |hwdb_entry_name| in the following file:
   ``${CY_DIR}/sims/firesim-staging/sample_config_hwdb.yaml``.
3. Download/extract the ``.tar.gz`` file to a known location. Inside, you will find
   ``xilinx_alveo_v80/firesim.pdi``. Note its full path for the next steps.
4. The flash is split into two partitions; the V80 boots from partition 0. Save a copy
   of the factory image, which is in partition 0, to partition 1 (replace ``01:00.0``
   with your V80's PCIe address):

   .. code-block:: bash

       sudo ami_tool cfgmem_copy -d 01:00.0 -i 0 -p 1

   Newer AMI releases name the partitions ``primary:0`` and ``primary:1`` instead; see
   ``ami_tool cfgmem_copy -h``.

5. Write the FireSim image to partition 0:

   .. code-block:: bash

       sudo ami_tool cfgmem_program -d 01:00.0 -i /path/to/xilinx_alveo_v80/firesim.pdi -p 0 -q

   This can take over 10 minutes. Do not interrupt it or power off the machine until AMI
   reports that the image was programmed successfully. ``-q`` skips booting the new
   image right away; we cold-boot instead.

6. Repeat steps 4 and 5 for each V80, then unload AMI:

   .. code-block:: bash

       sudo rmmod ami

7. Power off your machine fully, then cold-boot it.
8. Once the machine has booted, run the following to ensure that your FPGA is set up
   properly:

   .. code-block:: bash

       lspci -vvv -d 10ee:903f

   Each V80 should now show up as a ``Memory controller`` (PCIe class ``0580``) with two
   memory regions (512K and 32M). There should be one entry for each V80 you've added to
   the Run Farm Machine.

Partition 1 now holds a copy of the factory image. To go back to the factory image, use
the same AMD instructions as for restoring it above.

.. note::

    Remember to keep the JTAG cable connected at all times when running FireSim
    simulations (it is used to program the FPGA).

Machines with other FireSim FPGAs
---------------------------------

All FireSim Xilinx FPGA images show up on PCIe as ``10ee:903f``. FireSim tells V80s
apart from XDMA-based boards (e.g., Alveo U250) by their PCIe class: V80s report
``0580``, and the other boards report a different class. Each FireSim run uses one board
type per Run Farm Machine, so if a machine has both V80s and XDMA-based FPGAs, use a
separate ``config_runtime.yaml`` for each board type, and give each board type its own
FPGA database on that machine with the ``override_fpga_db`` host option (see
:gh-file-ref:`deploy/run-farm-recipes/externally_provisioned.yaml`).
