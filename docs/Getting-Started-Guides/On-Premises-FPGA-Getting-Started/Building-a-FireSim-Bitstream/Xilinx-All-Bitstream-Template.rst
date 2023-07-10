Configuring a Build in the Manager
-------------------------------------

In the ``deploy/config_build.yaml`` file, you will notice that the ``builds_to_run``
section currently contains several lines, which
indicates to the build system that you want to run all of these "build recipes" in
parallel, with the parameters for each "build recipe" listed in the relevant section of the
``deploy/config_build_recipes.yaml`` file.

In this guide, we'll build the default FireSim design for the |fpga_name|, which is specified
by the |hwdb_entry_name| section in ``deploy/config_build_recipes.yaml``.
This was the same configuration used to build the pre-built bitstream that you used to run
simulations in the guide to running a simulation.

Looking at the |hwdb_entry_name| section in ``deploy/config_build_recipes.yaml``,
there are a few notable items:

* ``TARGET_CONFIG`` specifies that this configuration is a simple singlecore RISC-V Rocket with a single DRAM channel.

* ``bit_builder_recipe`` points to |bit_builder_path|, which is found in the :gh-file-ref:`deploy` directory and tells the FireSim build system how to build bitstreams for this FPGA.

Having looked at this entry, let's now set up the build in ``deploy/config_build.yaml``. First, we'll set up the ``build_farm`` mapping, which specifies the Build Farm Machines that are available to build FPGA bitstreams.

* ``base_recipe`` will map to ``build-farm-recipes/externally_provisioned.yaml``. This indicates to the FireSim manager that the machines used to run builds are existing machines that have been set up by the user, instead of cloud instances that are automatically provisioned.

* ``default_build_dir`` is the directory in which builds will run out of on your Build Farm Machines. Change the default ``null`` to a path where you would like temporary build data to be stored on your Build Farm Machines.

* ``build_farm_hosts`` is a section that contains a list of IP addresses or hostnames of machines in your Build Farm. By default, ``localhost`` is specified. If you are using a separate Build Farm Machine, you should replace this with the IP address or hostname of the Build Farm Machine on which you would like to run the build.

Having configured our Build Farm, let's specify the design we'd like to build. To do this, edit the ``builds_to_run`` section in ``deploy/config_build.yaml`` so that it looks like the following:

.. code-block:: text
   :substitutions:

   builds_to_run:
       - |hwdb_entry_name_non_code|


In essence, you should delete or comment out all the other items in the ``builds_to_run`` section besides |hwdb_entry_name|.


Running the Build
----------------------

Now, we can run a build like so:

.. code-block:: bash

    firesim buildbitstream

This will run through the entire build process, taking the Chisel (or Verilog) RTL
and producing a bitstream that runs on the |fpga_name| FPGA. This whole process will
usually take a few hours. When the build
completes, you will see a directory in
``deploy/results-build/``, named after your build parameter
settings, that contains all of the outputs of the |builder_name| build process.
Additionally, the manager will print out a path to a log file
that describes everything that happened, in-detail, during this run (this is a
good file to send us if you encounter problems).

The manager will also print an entry that can be added to ``config_hwdb.yaml`` so that the
bitstream can be used to run simulations. This entry will contain a ``bitstream_tar`` key whose
value is the path to the final generated bitstream file. You can share generated bitstreams
with others by sharing the file listed in ``bitstream_tar`` and the ``config_hwdb.yaml``
entry for it.

Now that you know how to generate your own FPGA image, you can modify the target-design
to add your own features, then build a FireSim-compatible FPGA image automatically!

This is the end of the Getting Started Guide. To learn more advanced FireSim
features, you can choose a link under the "Advanced Docs" section to the left.
