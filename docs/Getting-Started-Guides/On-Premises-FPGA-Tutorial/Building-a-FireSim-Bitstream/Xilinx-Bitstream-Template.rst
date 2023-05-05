Building Your Own Hardware Designs
==================================

This section will guide you through building a |fpga_name| FPGA |bit_file_type| (FPGA image) for a FireSim simulation.

Build Recipes
---------------

We already provide for you a build recipe (i.e. hardware configuration) called |hwdb_entry_name| that was used to pre-build a |fpga_name| FPGA |bit_file_type|.
You can find this in the ``config_build_recipes.yaml`` file.
This configuration is a simple singlecore Rocket configuration with a single DRAM channel and no debugging features (as indicated by some of the variables like ``TARGET_CONFIG``).
Additionally, this configuration has a field called ``bit_builder_recipe`` pointing to |bit_builder_path|.
This file found in the :gh-file-ref:`deploy` tells the FireSim build system what combination of commands to run to build the |bit_file_type|.

Next, lets build the bitstream corresponding to the build recipe and specify the Build Farm to run on.
In the ``deploy/config_build.yaml`` file, you will notice at least two mappings: ``build_farm`` and ``builds_to_run``.
Let's first finishing setting up the the ``build_farm`` mapping which specifies the build machines that are available to build FPGA images.
First, notice that the ``base_recipe`` maps to ``build-farm-recipes/externally_provisioned.yaml``.
This indicates to the FireSim manager that the machines allocated to run builds will be provided by the user through IP addresses
instead of being automatically launched and allocated (e.g. launching instances on-demand in AWS).
Next, let's look at the ``build_farm_hosts`` list that has a single element ``localhost``.
This list indicates the IP addresses of machines already booted and ready to use for builds.
In our case, we are building locally so we provide our own IP address, ``localhost``.
Finally, let's look at and modify the ``default_build_dir`` mapping to a directory of your choice that will store
temporary |builder_name| build files during builds.

Continuing to the next section in the ``deploy/config_build.yaml`` file, you will notice that the ``builds_to_run``
section currently contains several lines, which
indicates to the build system that you want to run all of these builds on the machines provided, with the parameters listed in the relevant section of the
``deploy/config_build_recipes.yaml`` file.

To start out, let's build our simple design, |hwdb_entry_name|, that we previously added.
To do so, comment out all of the other build entries in ``deploy/config_build.yaml``, and uncomment the "- |hwdb_entry_name_non_code|" line.
So, you should end up with something like this (a line beginning with a ``#`` is a comment):

.. code-block:: yaml
   :substitutions:

   builds_to_run:
       # this section references builds defined in config_build_recipes.yaml
       # if you add a build here, it will be built when you run buildbitstream
       # Many other commented lines...
       - |hwdb_entry_name_non_code|


Running a Build
----------------------

Now, we can run a build like so:

.. code-block:: bash

    firesim buildbitstream

This will run through the entire build process, taking the Chisel RTL
and producing an |fpga_name| FPGA |bit_file_type| that runs on the FPGA. This whole process will
usually take a few hours. When the build
completes, you will see a directory in
``deploy/results-build/``, named after your build parameter
settings, that contains all of the outputs of the |builder_name| build process.
Additionally, the manager will print out a path to a log file
that describes everything that happened, in-detail, during this run (this is a
good file to send us if you encounter problems).

Now that you know how to generate your own FPGA image, you can modify the target-design
to add your own features, then build a FireSim-compatible FPGA image automatically!
To learn more advanced FireSim features, you can choose a link under the "Advanced Docs" section to the left.
