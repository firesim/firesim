.. |fpga_name| replace:: Xilinx Vitis-enabled U250
.. |vitis_version| replace:: 2022.1
.. |vitis_link| replace:: https://www.xilinx.com/products/design-tools/vitis/vitis-whats-new.html#20221

FPGA and Tool Setup
===================

Requirements and Installations
------------------------------

We require a base machine that is able to support a |fpga_name| and running Xilinx Vitis.
For the purposes of this tutorial, we assume you are running with a |fpga_name|.
Please refer to the minimum system requirements given in the following link: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Minimum-System-Requirements.
``sudo`` access is not needed for the machine except for when the |fpga_name| and corresponding software is installed.

Next, install the |fpga_name| as indicated: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Card-Installation-Procedures

We require the following programs/packages installed from the Xilinx website in addition to a physical |fpga_name| installation:

* Xilinx Vitis |vitis_version|

  * Installation link: |vitis_link|

* Xilinx XRT and |fpga_name| board package (corresponding with Vitis |vitis_version|)

  * Ensure you complete the "Installing the Deployment Software" and "Card Bring-Up and Validation" sections in the following link: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Installing-the-Deployment-Software

Setup Validation
----------------

After installing the |fpga_name| using the Xilinx instructions and installing the specific versions of Vitis/XRT, let's verify that the |fpga_name| can be used for emulations.
Ensure that you can run the following XRT commands without errors:

.. code-block:: bash
   :substitutions:

   xbutil examine # obtain the BDF associated with your installed |fpga_name|
   xbutil validate --device <CARD_BDF_INSTALLED> --verbose

The ``xbutil validate`` command runs simple tests to ensure that the FPGA can be properly flashed with a bitstream by using XRT.

.. Warning:: Anytime the host computer is rebooted you may need to re-run parts of the setup process (i.e. re-flash the shell).
     Before continuing to FireSim simulations after a host computer reboot, ensure that the previously mentioned ``xbutil`` command is successful.

Now you're ready to continue with other FireSim setup!
