Building a FireSim AFI (FPGA Image)
===================================

This section will guide you through building an AFI image for a FireSim
simulation.

During the build process, the build system will need to upload a tar
file to Amazon S3 in order to complete the build process using Amazon's
backend scripts (which convert the Vivado-generated tar into an AFI).
The manager will create this bucket for you automatically, you just need
to specify a name:

Choose a bucket name, e.g. ``firesim-yourname``. Bucket names must be
globally unique. If you choose one that's already taken, the manager
will notice and complain when you tell it to build an AFI. To set your
bucket name, open ``deploy/config_build.ini`` in your editor and under the
``[afibuild]`` header, replace

::

    s3bucketname=existing-bucket-name

with your own bucket name:

::

    s3bucketname=firesim-username

In the ``config_build.ini`` file, you will notice that the ``[builds]``
section currently contains one uncommented line ``build1``, which
indicates to the build system that you want to run a single build, with
the parameters listed in the ``[build1]`` section of the
``config_build.ini`` file. Here you can set parameters of the simulated
system, and also select the type of instance on which the Vivado build
will be deployed. From our experimentation, there are diminishing
returns using anything above a ``c4.4xlarge``, so we default to that.

You can change these parameters if you want, but the defaults work as an
example.

Now, we can run a build like so:

::

    firesim buildafi

This will run through the entire build process, taking your Chisel RTL
and producing an AFI/AGFI that runs on the FPGA. When the build
completes, you will see a directory in
``deploy/results-build/``, named after your build parameter
settings, that contains AGFI information (the ``AGFI_INFO`` file) and
all of the outputs of the Vivado build process (in the ``cl_firesim``
subdirectory). If you provided the manager with your email address, you
will also receive an email upon build completion.

Hit Next to continue to the next page.
