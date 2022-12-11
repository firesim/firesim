Building Your Own Hardware Designs (FireSim FPGA Images)
===========================================================

This section will guide you through building an AFI image for a FireSim
simulation.

Amazon S3 Setup
---------------

During the build process, the build system will need to upload a tar
file to Amazon S3 in order to complete the build process using Amazon's
backend scripts (which convert the Vivado-generated tar into an AFI).
The manager will create this bucket for you automatically, you just need
to specify a name.

So, choose a bucket name, e.g. ``firesim``. Bucket names must be
globally unique. If you choose one that's already taken, the manager
will notice and complain when you tell it to build an AFI. To set your
bucket name, open ``deploy/bit-builder-recipes/f1.yaml`` in your editor and under the
particular recipe you plan to build, replace

::

    s3_bucket_name: firesim

with your own bucket name, e.g.:

::

    s3_bucket_name: firesim

.. Note:: This isn't necessary if you set the ``append_userid_region`` key/value pair to ``true``.

Build Recipes
---------------

In the ``deploy/config_build.ini`` file, you will notice that the ``builds_to_run``
section currently contains several lines, which
indicates to the build system that you want to run all of these builds in
parallel, with the parameters listed in the relevant section of the
``deploy/config_build_recipes.ini`` file. Here you can set parameters of the simulated
system, and also select the type of instance on which the Vivado build will be
deployed. From our experimentation, there are diminishing returns using
anything above a ``z1d.2xlarge``, so we default to that. If you do wish to use a
different build instance type keep in mind that Vivado will consume in excess
of 32 GiB for large designs.


To start out, let's build a simple design, ``firesim_rocket_quadcore_no_nic_l2_llc4mb_ddr3``.
This is a design that has four cores, no nic, and uses the 4MB LLC + DDR3 memory model.
To do so, comment out all of the other build entries in ``deploy/config_build.ini``, besides the one we want. So, you should
end up with something like this (a line beginning with a ``#`` is a comment):

::

   builds_to_run:
       # this section references builds defined in config_build_recipes.ini
       # if you add a build here, it will be built when you run buildbitstream
       - firesim_rocket_quadcore_no_nic_l2_llc4mb_ddr3


Running a Build
----------------------

Now, we can run a build like so:

::

    firesim buildbitstream

This will run through the entire build process, taking the Chisel RTL
and producing an AFI/AGFI that runs on the FPGA. This whole process will
usually take a few hours. When the build
completes, you will see a directory in
``deploy/results-build/``, named after your build parameter
settings, that contains AGFI information (the ``AGFI_INFO`` file) and
all of the outputs of the Vivado build process (in the ``cl_firesim``
subdirectory). Additionally, the manager will print out a path to a log file
that describes everything that happened, in-detail, during this run (this is a
good file to send us if you encounter problems). If you provided the manager
with your email address, you will also receive an email upon build completion,
that should look something like this:

.. figure:: /img/build_complete_email.png
   :alt: Build Completion Email

   Build Completion Email


Now that you know how to generate your own FPGA image, you can modify the target-design
to add your own features, then build a FireSim-compatible FPGA image automatically!
To learn more advanced FireSim features, you can choose a link under the "Advanced
Docs" section to the left.
