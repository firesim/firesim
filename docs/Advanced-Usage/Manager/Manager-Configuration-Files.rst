.. _manager-configuration-files:

Manager Configuration Files
===============================

This page contains a centralized reference for all of the configuration options
in ``config_runtime.yaml``, ``config_build.yaml``, ``config_build_farm.yaml``,
``config_build_recipes.yaml``, and ``config_hwdb.yaml``. It also contains
references for all build and run farm recipes (in ``deploy/build-farm-recipes/`` and ``deploy/run-farm-recipes/``).

.. _config-runtime:

``config_runtime.yaml``
--------------------------

Here is a sample of this configuration file:

.. literalinclude:: /../deploy/sample-backup-configs/sample_config_runtime.yaml
   :language: yaml

Below, we outline each mapping in detail.

.. _run-farm-config-in-config-runtime:

``run_farm``
^^^^^^^^^^^^^^^^^^^

The ``run_farm`` mapping specifies the characteristics of your FireSim run farm so that the
manager can automatically launch them, run workloads on them, and terminate
them.

``base_recipe``
"""""""""""""""

The ``base_recipe`` key/value pair specifies the default set of arguments to use for a particular run farm type.
To change the run farm type, a new ``base_recipe`` file must be provided from ``deploy/run-farm-recipes``.
You are able to override the arguments given by a ``base_recipe`` by adding keys/values to the ``recipe_arg_overrides`` mapping.

``recipe_arg_overrides``
""""""""""""""""""""""""

This optional mapping of keys/values allows you to override the default arguments provided by the ``base_recipe``.
This mapping must match the same mapping structure as the ``args`` mapping within the ``base_recipe`` file given.
Overridden arguments override recursively such that all key/values present in the override args replace the default arguments given
by the ``base_recipe``. In the case of sequences, a overridden sequence completely replaces the corresponding sequence in the default args.
Additionally, it is not possible to change the default run farm type through these overrides.
This must be done by changing the default ``base_recipe``.

See :ref:`run-farm-recipe` for more details on the potential run farm recipes that can be used.

``metasimulation``
^^^^^^^^^^^^^^^^^^

The ``metasimulation`` options below allow you to run metasimulations
instead of FPGA simulations when doing ``launchrunfarm``, ``infrasetup``, and ``runworkload``.
See :ref:`metasimulation` for more details.

``metasimulation_enabled``
"""""""""""""""""""""""""""""

This is a boolean to enable running metasimulations in-place of FPGA-accelerated simulations.
The number of metasimulations that are run on a specific Run Farm host is determined by the ``num_metasims`` argument
in each run farm recipe (see :ref:`run-farm-recipe`).

``metasimulation_host_simulator``
""""""""""""""""""""""""""""""""""

This key/value pair chooses which RTL simulator should be used for metasimulation.
Options include ``verilator`` and ``vcs`` if waveforms are unneeded and ``*-debug`` versions
if a waveform is needed.

``metasimulation_only_plusargs``
""""""""""""""""""""""""""""""""""

This key/value pair is a string that passes plusargs (arguments with a ``+`` in front) to the metasimulations.

``metasimulation_only_vcs_plusargs``
"""""""""""""""""""""""""""""""""""""

This key/value pair is a string that passes plusargs (arguments with a ``+`` in front) to metasimulations using ``vcs`` or ``vcs-debug``.

``target_config``
^^^^^^^^^^^^^^^^^^^

The ``target_config`` options below allow you to specify the high-level
configuration of the target you are simulating. You can change these parameters
after launching a Run Farm (assuming you have the correct number of instances),
but in many cases you will need to re-run the ``infrasetup`` command to make
sure the correct simulation infrastructure is available on your instances.

``topology``
"""""""""""""""""""""""""""""

This field dictates the network topology of the simulated system. Some examples:

``no_net_config``: This runs N (see ``no_net_num_nodes`` below) independent
simulations, without a network simulation. You can currently only use this
option if you build one of the NoNIC hardware configs of FireSim.

``example_8config``: This requires a single ``f1.16xlarge``, which will
simulate 1 ToR switch attached to 8 simulated servers.

``example_16config``: This requires two ``f1.16xlarge`` instances and one
``m4.16xlarge`` instance, which will
simulate 2 ToR switches, each attached to 8 simulated servers, with the two
ToR switches connected by a root switch.

``example_64config``: This requires eight ``f1.16xlarge`` instances and one
``m4.16xlarge`` instance, which will simulate 8 ToR switches, each attached to
8 simulated servers (for a total of 64 nodes), with the eight ToR switches
connected by a root switch.

Additional configurations are available in ``deploy/runtools/user_topology.py``
and more can be added there. See the :ref:`usertopologies` section
for more info.

``no_net_num_nodes``
"""""""""""""""""""""""""""""

This determines the number of simulated nodes when you are using
``topology: no_net_config``.

``link_latency``
"""""""""""""""""

In a networked simulation, this allows you to specify the link latency of the
simulated network in CYCLES. For example, 6405 cycles is roughly 2 microseconds
at 3.2 GHz. A current limitation is that this value (in cycles) must be
a multiple of 7. Furthermore, you must not exceed the buffer size specified
in the NIC's simulation widget.

``switching_latency``
""""""""""""""""""""""

In a networked simulation, this specifies the minimum port-to-port switching
latency of the switch models, in CYCLES.

``net_bandwidth``
""""""""""""""""""""""

In a networked simulation, this specifies the maximum output bandwidth that a
NIC is allowed to produce as an integer in Gbit/s. Currently, this must be a
number between 1 and 200, allowing you to model NICs between 1 and 200 Gbit/s.

``profile_interval``
"""""""""""""""""""""""""""""

The simulation driver periodically samples performance counters in FASED timing model instances and dumps the result to a file on the host.
``profile_interval`` defines the number of target cycles between samples; setting this field to -1 disables polling.


``default_hw_config``
"""""""""""""""""""""""""""""

This sets the server configuration launched by default in the above topologies.
Heterogeneous configurations can be achieved by manually specifying different
names within the topology itself, but all the ``example_Nconfig`` configurations
are homogeneous and use this value for all nodes.

You should set this to one of the hardware configurations you have defined already in
``config_hwdb.yaml``.  You should set this to the NAME (mapping title) of the
hardware configuration from ``config_hwdb.yaml``, NOT the actual AGFI or ``xclbin`` itself
(NOT something like ``agfi-XYZ...``).


``tracing``
^^^^^^^^^^^^^^^^^^^

This section manages TracerV-based tracing at simulation runtime. For more
details, see the :ref:`tracerv` page for more details.

``enable``
""""""""""""""""""

This turns tracing on, when set to ``yes`` and off when set to ``no``. See the :ref:`tracerv-enabling`.

``output_format``
""""""""""""""""""""

This sets the output format for TracerV tracing. See the :ref:`tracerv-output-format` section.

``selector``, ``start``, and ``end``
"""""""""""""""""""""""""""""""""""""

These configure triggering for TracerV. See the :ref:`tracerv-trigger` section.


``autocounter``
^^^^^^^^^^^^^^^^^^^^^

This section configures AutoCounter. See the :ref:`autocounter` page for more details.

``read_rate``
"""""""""""""""""

This sets the rate at which AutoCounters are read. See the :ref:`autocounter-runtime-parameters` section for more details.


``workload``
^^^^^^^^^^^^^^^^^^^

This section defines the software that will run on the simulated system.

``workload_name``
"""""""""""""""""

This selects a workload to run across the set of simulated nodes.
A workload consists of a series of jobs that need to be run on simulated
nodes (one job per node).

Workload definitions are located in ``firesim/deploy/workloads/*.json``.

Some sample workloads:

``linux-uniform.json``: This runs the default FireSim Linux distro on as many nodes
as you specify when setting the ``target_config`` parameters.

``spec17-intrate.json``: This runs SPECint 2017's rate benchmarks. In this type of
workload, you should launch EXACTLY the correct number of nodes necessary to run the
benchmark. If you specify fewer nodes, the manager will warn that not all jobs were
assigned to a simulation. If you specify too many simulations and not enough
jobs, the manager will not launch the jobs.

Others can be found in the aforementioned directory. For a description of the
JSON format, see :ref:`defining-custom-workloads`.


``terminate_on_completion``
"""""""""""""""""""""""""""

Set this to ``no`` if you want your Run Farm to keep running once the workload
has completed. Set this to ``yes`` if you want your Run Farm to be TERMINATED
after the workload has completed and results have been copied off.

``suffix_tag``
""""""""""""""""""""""""""

This allows you to append a string to a workload's output directory name,
useful for differentiating between successive runs of the same workload,
without renaming the entire workload. For example, specifying
``suffix_tag: test-v1`` with a workload named ``super-application`` will result
in a workload results directory named
``results-workload/DATE--TIME-super-application-test-v1/``.

``host_debug``
^^^^^^^^^^^^^^^^^^

``zero_out_dram``
"""""""""""""""""""""""""""""

Set this to ``yes`` to zero-out FPGA-attached DRAM before simulation begins.
This process takes 2-5 minutes. In general, this is not required to produce
deterministic simulations on target machines running linux, but should be
enabled if you observe simulation non-determinism.

``disable_synth_asserts``
"""""""""""""""""""""""""""""

Set this to ``yes`` to make the simulation ignore synthesized assertions when
they fire. Otherwise, simulation will print the assertion message and terminate
when an assertion fires.


.. _config-build:

``config_build.yaml``
--------------------------

Here is a sample of this configuration file:

.. literalinclude:: /../deploy/sample-backup-configs/sample_config_build.yaml
   :language: yaml

Below, we outline each mapping in detail.

``build_farm``
^^^^^^^^^^^^^^^^^^^

In this section, you specify the specific build farm configuration that you wish to use to build FPGA bitstreams.

``base_recipe``
"""""""""""""""

The ``base_recipe`` key/value pair specifies the default set of arguments to use for a particular build farm type.
To change the build farm type, a new ``base_recipe`` file must be provided from ``deploy/build-farm-recipes``.
You are able to override the arguments given by a ``base_recipe`` by adding keys/values to the ``recipe_arg_overrides`` mapping.

See :ref:`build-farm-recipe` for more details on the potential build farm recipes that can be used.

``recipe_arg_overrides``
""""""""""""""""""""""""

This optional mapping of keys/values allows you to override the default arguments provided by the ``base_recipe``.
This mapping must match the same mapping structure as the ``args`` mapping within the ``base_recipe`` file given.
Overridden arguments override recursively such that all key/values present in the override args replace the default arguments given
by the ``base_recipe``. In the case of sequences, a overridden sequence completely replaces the corresponding sequence in the default args.
Additionally, it is not possible to change the default build farm type through these overrides.
This must be done by changing the default ``base_recipe``.

``builds_to_run``
^^^^^^^^^^^^^^^^^^^^^

In this section, you can list as many build entries as you want to run
for a particular call to the ``buildbitstream`` command (see
:ref:`config-build-recipes` below for how to define a build entry). For
example, if we want to run the builds named ``awesome_firesim_config`` and ``quad_core_awesome_firesim_config``, we would
write:

::

    builds_to_run:
        - awesome_firesim_config
        - quad_core_awesome_firesim_config


``agfis_to_share``
^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. Warning:: This is only used in the AWS EC2 case.

This is used by the ``shareagfi`` command to share the specified agfis with the
users specified in the next (``share_with_accounts``) section. In this section,
you should specify the section title (i.e. the name you made up) for a hardware
configuration in ``config_hwdb.yaml``. For example, to share the hardware config:

::

    firesim_rocket_quadcore_nic_l2_llc4mb_ddr3:
        # this is a comment that describes my favorite configuration!
        agfi: agfi-0a6449b5894e96e53
        deploy_triplet_override: null
        custom_runtime_config: null

you would use:

::

    agfis_to_share:
        - firesim_rocket_quadcore_nic_l2_llc4mb_ddr3


``share_with_accounts``
^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. Warning:: This is only used in the AWS EC2 case.

A list of AWS account IDs that you want to share the AGFIs listed in
``agfis_to_share`` with when calling the manager's ``shareagfi`` command. You
should specify names in the form ``usersname: AWSACCTID``. The left-hand-side is
just for human readability, only the actual account IDs listed here matter. If you specify ``public: public`` here, the AGFIs are shared publicly, regardless of any other entires that are present.

.. _config-build-recipes:

``config_build_recipes.yaml``
--------------------------------

Here is a sample of this configuration file:

.. literalinclude:: /../deploy/sample-backup-configs/sample_config_build_recipes.yaml
   :language: yaml

Below, we outline each section and parameter in detail.


Build definition sections, e.g. ``awesome_firesim_config``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In this file, you can specify as many build definition sections as you want,
each with a header like ``awesome_firesim_config`` (i.e. a nice, short name
you made up). Such a section must contain the following fields:

``DESIGN``
"""""""""""""""""""""""""""""

This specifies the basic target design that will be built. Unless you
are defining a custom system, this should be set to ``FireSim``.
We describe this in greater detail in :ref:`Generating Different
Targets<generating-different-targets>`).

``TARGET_CONFIG``
"""""""""""""""""""

This specifies the hardware configuration of the target being simulated. Some
examples include ``FireSimRocketConfig`` and ``FireSimQuadRocketConfig``.
We describe this in greater detail in :ref:`Generating Different
Targets<generating-different-targets>`).


``PLATFORM_CONFIG``
"""""""""""""""""""""

This specifies parameters to pass to the compiler (Golden Gate). Notably,
PLATFORM_CONFIG can be used to enable debugging tools like assertion synthesis,
and resource optimizations like instance multithreading.  Critically, it also
calls out the host-platform (e.g., F1 or Vitis) to compile against: this
defines the widths of internal simulation interfaces and specifies resource
limits (e.g., how much DRAM is available on the platform).

``platform_build_args``
''''''''''''''''''''''''

These configure the bitstream build, and are host-platform-agnostic.
Platform-specific arguments, like the Vitis platform ("DEVICE"), are captured
as arguments to the bitbuilder.

``fpga_frequency``
~~~~~~~~~~~~~~~~~~~~~~~~

Specifies the host FPGA frequency for a bitstream build.

``build_strategy``
~~~~~~~~~~~~~~~~~~~~~~~~

Specifies a pre-canned set of strategies and directives to pass to the
bitstream build. Note, these are implemented differently on different host
platforms, but try to optimize for the same things. Strategies supported across both Vitis and EC2 F1 include:

 - ``TIMING``: Optimize for improved fmax.
 - ``AREA``: Optimize for reduced resource utilization.

Names are derived AWS's strategy set.

``deploy_triplet``
""""""""""""""""""

This allows you to override the ``deploytriplet`` stored with the AGFI.
Otherwise, the ``DESIGN``/``TARGET_CONFIG``/``PLATFORM_CONFIG`` you specify
above will be used. See the AGFI Tagging section for more details. Most likely,
you should leave this set to ``null``. This is usually only used if you have
proprietary RTL that you bake into an FPGA image, but don't want to share with
users of the simulator.

``TARGET_PROJECT`` `(Optional)`
"""""""""""""""""""""""""""""""

This specifies the target project in which the target is defined (this is described
in greater detail :ref:`here<generating-different-targets>`).  If
``TARGET_PROJECT`` is undefined the manager will default to ``firesim``.
Setting ``TARGET_PROJECT`` is required for building the MIDAS examples
(``TARGET_PROJECT: midasexamples``) with the manager, or for building a
user-provided target project.

``post_build_hook``
"""""""""""""""""""""""

(Optional) Provide an a script to run on the results copied back
from a _single_ build instance. Upon completion of each design's build,
the manager invokes this script and passing the absolute path to that instance's
build-results directory as it's first argument.

``metasim_customruntimeconfig``
""""""""""""""""""""""""""""""""

This is an advanced feature - under normal conditions, you can use the default
parameters generated automatically by the simulator by setting this field to
``null`` for metasimulations. If you want to customize runtime parameters for certain parts of
the metasimulation (e.g. the DRAM model's runtime parameters), you can place
a custom config file in ``sim/custom-runtime-configs/``. Then, set this field
to the relative name of the config. For example,
``sim/custom-runtime-configs/GREATCONFIG.conf`` becomes
``metasim_customruntimeconfig: GREATCONFIG.conf``.

``bit_builder_recipe``
"""""""""""""""""""""""

This specifies the bitstream type to generate for a particular recipe (ex. build a Vitis ``xclbin``).
This must point to a file in ``deploy/bit-builder-recipes/``.
See :ref:`bit-builder-recipe` for more details on bit builders and their arguments.

``bit_builder_arg_overrides``
""""""""""""""""""""""""""""""

This optional mapping of keys/values allows you to override the default arguments provided by the ``bit_builder_recipe``.
This mapping must match the same mapping structure as the ``args`` mapping within the ``bit_builder_recipe`` file given.
Overridden arguments override recursively such that all key/values present in the override args replace the default arguments given
by the ``bit_builder_recipe``. In the case of sequences, a overridden sequence completely replaces the corresponding sequence in the default args.
Additionally, it is not possible to change the default bit builder type through these overrides.
This must be done by changing the default ``bit_builder_recipe``.

.. _config-hwdb:

``config_hwdb.yaml``
---------------------------

Here is a sample of this configuration file:

.. literalinclude:: /../deploy/sample-backup-configs/sample_config_hwdb.yaml
   :language: yaml


This file tracks hardware configurations that you can deploy as simulated nodes
in FireSim. Each such configuration contains a name for easy reference in higher-level
configurations, defined in the section header, an handle to a bitstream (an AGFI or ``xclbin`` path), which represents the
FPGA image, a custom runtime config, if one is needed, and a deploy triplet
override if one is necessary.

When you build a new bitstream, you should put the default version of it in this
file so that it can be referenced from your other configuration files (the AGFI ID or ``xclbin`` path).

The following is an example section from this file - you can add as many of
these as necessary:

.. literalinclude:: /../deploy/sample-backup-configs/sample_config_hwdb.yaml
   :language: yaml
   :start-after: DOCREF START: Example HWDB Entry
   :end-before: DOCREF END: Example HWDB Entry

``NAME_GOES_HERE``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In this example, ``firesim_rocket_quadcore_nic_l2_llc4mb_ddr3`` is the name that will be
used to reference this hardware design in other configuration locations. The following
items describe this hardware configuration:

``agfi``
"""""""""""""""

This represents the AGFI (FPGA Image) used by this hardware configuration.
Only used in AWS EC2 F1 FireSim configurations (a ``xclbin`` key/value cannot exist with this
key/value in the same recipe).

``xclbin``
"""""""""""""""

This represents a path to a bitstream (FPGA Image) used by this hardware configuration.
This path must be local to the run farm host that the simulation runs on.
Only used in Vitis FireSim configurations (an ``agfi`` key/value cannot exist with this
key/value in the same recipe)


``deploy_triplet_override``
"""""""""""""""""""""""""""""

This is an advanced feature - under normal conditions, you should leave this set to ``null``, so that the
manager uses the configuration triplet that is automatically stored with the
bitstream metadata at build time. Advanced users can set this to a different
value to build and use a different driver when deploying simulations. Since
the driver depends on logic now hardwired into the
FPGA bitstream, drivers cannot generally be changed without requiring FPGA
recompilation.


``custom_runtime_config``
"""""""""""""""""""""""""""""

This is an advanced feature - under normal conditions, you can use the default
parameters generated automatically by the simulator by setting this field to
``null``. If you want to customize runtime parameters for certain parts of
the simulation (e.g. the DRAM model's runtime parameters), you can place
a custom config file in ``sim/custom-runtime-configs/``. Then, set this field
to the relative name of the config. For example,
``sim/custom-runtime-configs/GREATCONFIG.conf`` becomes
``custom_runtime_config: GREATCONFIG.conf``.


``driver_tar``
"""""""""""""""""""""""""""""

When this key is present, the local driver will not build from source.
Instead, during `firesim infrasetup`, this file will be deployed and extracted
into the `sim_slot_X` folder on the run farm instance. This file may
be a `.tar`, `.tar.gz`, `.tar.bz2` or any other format that GNU tar (version 1.26)
can automatically detect. The purpose of this feature is to enable advanced CI
configurations where the driver build step is decoupled. For now this can
only accept a path to a file on the manager's local filesystem.
In a future update, full URI support will be added.



Add more hardware config sections, like ``NAME_GOES_HERE_2``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You can add as many of these entries to ``config_hwdb.yaml`` as you want, following the format
discussed above (i.e. you provide ``agfi`` or ``xclbin``, ``deploy_triplet_override``, and ``custom_runtime_config``).

.. _run-farm-recipe:

Run Farm Recipes (``run-farm-recipes/*``)
------------------------------------------

Here is an example of this configuration file:

.. literalinclude:: /../deploy/run-farm-recipes/aws_ec2.yaml
   :language: yaml

``run_farm_type``
^^^^^^^^^^^^^^^^^

This key/value specifies a run farm class to use for launching, managing, and terminating
run farm hosts used for simulations.
By default, run farm classes can be found in :gh-file-ref:`deploy/runtools/run_farm.py`. However, you can specify
your own custom run farm classes by adding your python file to the ``PYTHONPATH``.
For example, to use the ``AWSEC2F1`` build farm class, you would write ``run_farm_type: AWSEC2F1``.

``args``
^^^^^^^^^^^^^^^^^^^^^

This section specifies all arguments needed for the specific ``run_farm_type`` used.
For a list of arguments needed for a run farm class, users should refer to
the ``_parse_args`` function in the run farm class given by ``run_farm_type``.

``aws_ec2.yaml`` run farm recipe
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This run farm recipe configures a FireSim run farm to use AWS EC2 instances.

Here is an example of this configuration file:

.. literalinclude:: /../deploy/run-farm-recipes/aws_ec2.yaml
   :language: yaml

``run_farm_tag``
""""""""""""""""

Use ``run_farm_tag`` to differentiate between different Run Farms in FireSim.
Having multiple ``config_runtime.yaml`` files with different ``run_farm_tag``
values allows you to run many experiments at once from the same manager instance.

The instances launched by the ``launchrunfarm`` command will be tagged with
this value. All later operations done by the manager rely on this tag, so
you should not change it unless you are done with your current Run Farm.

Per AWS restrictions, this tag can be no longer than 255 characters.

``always_expand_runfarm``
"""""""""""""""""""""""""
When ``yes`` (the default behavior when not given) the number of instances
of each type (see ``f1.16xlarges`` etc. below)  are launched every time you
run ``launchrunfarm``.

When ``no``, ``launchrunfarm`` looks for already existing instances that
match ``run_farm_tag`` and treat ``f1.16xlarges`` (and other 'instance-type'
values below) as a total count.

For example, if you have ``f1.2xlarges`` set to 100 and the first time you
run ``launchrunfarm`` you have ``launch_instances_timeout_minutes`` set to 0
(i.e. giveup after receiving a ``ClientError`` for each AvailabilityZone) and
AWS is only able to provide you 75 ``f1.2xlarges`` because of capacity issues,
``always_expand_runfarm`` changes the behavior of ``launchrunfarm`` in subsequent
attempts.  ``yes`` means ``launchrunfarm`` will try to launch 100 ``f1.2xlarges``
again.  ``no`` means that ``launchrunfarm`` will only try to launch an additional
25 ``f1.2xlarges`` because it will see that there are already 75 that have been launched
with the same ``run_farm_tag``.

``launch_instances_timeout_minutes``
""""""""""""""""""""""""""""""""""""

Integer number of minutes that the ``launchrunfarm`` command will attempt to
request new instances before giving up.  This limit is used for each of the types
of instances being requested.  For example, if you set to 60,
and you are requesting all four types of instances, ``launchrunfarm`` will try
to launch each instance type for 60 minutes, possibly trying up to a total of
four hours.

This limit starts to be applied from the first time ``launchrunfarm`` receives a
``ClientError`` response in all AvailabilityZones (AZs) for your region.  In other words,
if you request more instances than can possibly be requested in the given limit but AWS
is able to satisfy all of the requests, the limit will not be enforced.

To experience the old (<= 1.12) behavior, set this limit to 0 and ``launchrunfarm``
will exit the first time it receives ``ClientError`` across all AZ's. The old behavior
is also the default if ``launch_instances_timeout_minutes`` is not included.

``run_instance_market``
""""""""""""""""""""""""

You can specify either ``spot`` or ``ondemand`` here, to use one of those
markets on AWS.

``spot_interruption_behavior``
""""""""""""""""""""""""""""""

When ``run_instance_market: spot``, this value determines what happens to an instance
if it receives the interruption signal from AWS. You can specify either
``hibernate``, ``stop``, or ``terminate``.

``spot_max_price``
"""""""""""""""""""""""""""""

When ``run_instance_market: spot``, this value determines the max price you are
willing to pay per instance, in dollars. You can also set it to ``ondemand``
to set your max to the on-demand price for the instance.

``default_simulation_dir``
"""""""""""""""""""""""""""""

This is the path on the run farm host that simulations will run out of.

``run_farm_hosts_to_use``
"""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""

This is a sequence of unique specifications (given by ``run_farm_host_specs``) to number of instances needed.
Set these key/value pairs respectively based on the number and types of instances
you need. While we could automate this setting, we choose not to, so that
users are never surprised by how many instances they are running.

Note that these values are ONLY used to launch instances. After launch, the
manager will query the AWS API to find the instances of each type that have the
``run_farm_tag`` set above assigned to them.

Also refer to ``always_expand_runfarm`` which determines whether ``launchrunfarm``
treats these counts as an incremental amount to be launched every time it is envoked
or a total number of instances of that type and ``run_farm_tag`` that should be made
to exist.  Note, ``launchrunfarm`` will never terminate instances.

``run_farm_host_specs``
"""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""

This is a sequence of specifications that describe a AWS EC2 instance and its properties.
A sequence consists of the AWS EC2 instance name (i.e. ``f1.2xlarge``) and number of FPGAs it supports
(``num_fpgas``), number of metasims it could support (``num_metasims``), and if the instance
should only host switch simulations (``use_for_switch_only``). Additionally, a specification can optionally add
``override_simulation_dir`` to override the ``default_simulation_dir`` for that specific run farm host.
Similarly, a specification can optionally add ``override_platform`` to choose a different default deploy manager platform for
that specific run farm host (for more details on this see the following section). By default, the deploy manager is setup
for AWS EC2 simulations.


``externally_provisioned.yaml`` run farm recipe
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This run farm is an allows users to provide an list of pre-setup unmanaged run farm hosts (by hostname or IP address) that
they can run simulations on.
Note that this run farm type does not launch or terminate the run farm hosts. This functionality should be handled by the user.
For example, users can use this run farm type to run simulations locally.

Here is an example of this configuration file:

.. literalinclude:: /../deploy/run-farm-recipes/externally_provisioned.yaml
   :language: yaml

``default_platform``
""""""""""""""""""""

This key/value specifies a default deploy platform (also known as a deploy manager) class to use for managing
simulations across all run farm hosts.
For example, this class manages how to flash FPGAs with bitstreams, how to copy back results, and how to check if a simulation is running.
By default, deploy platform classes can be found in :gh-file-ref:`deploy/runtools/run_farm_deploy_managers.py`. However, you can specify
your own custom run farm classes by adding your python file to the ``PYTHONPATH``.
There are two default deploy managers / platforms that correspond to AWS EC2 F1 FPGAs and Vitis FPGAs, ``EC2InstanceDeployManager`` and ``VitisInstanceDeployManager``, respectively.
For example, to use the ``EC2InstanceDeployManager`` deploy platform class, you would write ``default_platform: EC2InstanceDeployManager``.

``default_simulation_dir``
"""""""""""""""""""""""""""""

This is the default path on all run farm hosts that simulations will run out of.

``run_farm_hosts_to_use``
"""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""

This is a sequence of unique hostnames/IP address to specifications (given by ``run_farm_host_specs``).
Set these key/value pairs respectively to map unmanaged run farm hosts
to their description (given by a specification).
For example, to run simulations locally, a user can write a sequence element with ``- localhost: four_fpgas_spec`` to
indicate that ``localhost`` should be used and that it has a type of ``four_fpgas_spec``.

``run_farm_host_specs``
"""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""

This is a sequence of specifications that describe an unmanaged run farm host and its properties.
A sequence consists of the specification name (i.e. ``four_fpgas_spec``) and number of FPGAs it supports
(``num_fpgas``), number of metasims it could support (``num_metasims``), and if the instance
should only host switch simulations (``use_for_switch_only``). Additionally, a specification can optionally add
``override_simulation_dir`` to override the ``default_simulation_dir`` for that specific run farm host.
Similarly, a specification can optionally add ``override_platform`` to choose a different ``default_platform`` for
that specific run farm host.

.. _build-farm-recipe:

Build Farm Recipes (``build-farm-recipes/*``)
-----------------------------------------------

Here is an example of this configuration file:

.. literalinclude:: /../deploy/build-farm-recipes/aws_ec2.yaml
   :language: yaml

``build_farm_type``
^^^^^^^^^^^^^^^^^^^^

This key/value specifies a build farm class to use for launching, managing, and terminating
build farm hosts used for building bitstreams.
By default, build farm classes can be found in :gh-file-ref:`deploy/buildtools/buildfarm.py`. However, you can specify
your own custom build farm classes by adding your python file to the ``PYTHONPATH``.
For example, to use the ``AWSEC2`` build farm class, you would write ``build_farm_type: AWSEC2``.

``args``
^^^^^^^^^^^^^^^^^^^^^

This section specifies all arguments needed for the specific ``build_farm_type`` used.
For a list of arguments needed for a build farm class, users should refer to
the ``_parse_args`` function in the build farm class given by ``build_farm_type``.

``aws_ec2.yaml`` build farm recipe
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This build farm recipe configures a FireSim build farm to use AWS EC2 instances enabled with Vivado.

Here is an example of this configuration file:

.. literalinclude:: /../deploy/build-farm-recipes/aws_ec2.yaml
   :language: yaml

``instance_type``
""""""""""""""""""

The AWS EC2 instance name to run a bitstream build on. Note that for large designs, Vivado uses
an excess of 32 GiB so choose a non-default instance type wisely.

``build_instance_market``
""""""""""""""""""""""""""

You can specify either ``spot`` or ``ondemand`` here, to use one of those
markets on AWS.

``spot_interruption_behavior``
""""""""""""""""""""""""""""""

When ``run_instance_market: spot``, this value determines what happens to an instance
if it receives the interruption signal from AWS. You can specify either
``hibernate``, ``stop``, or ``terminate``.

``spot_max_price``
"""""""""""""""""""""""""""""

When ``build_instance_market: spot``, this value determines the max price you are
willing to pay per instance, in dollars. You can also set it to ``ondemand``
to set your max to the on-demand price for the instance.

``default_build_dir``
"""""""""""""""""""""""""""""

This is the path on the build farm host that bitstream builds will run out of.

``externally_provisioned.yaml`` build farm recipe
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This build farm recipe allows users to provide an list of pre-setup unmanaged build farm hosts (by hostname or IP address) that
they can run bitstream builds on.
Note that this build farm type does not launch or terminate the build farm hosts. This functionality should be handled by the user.
For example, users can use this build farm type to run bitstream builds locally.

Here is an example of this configuration file:

.. literalinclude:: /../deploy/build-farm-recipes/externally_provisioned.yaml
   :language: yaml

``default_build_dir``
"""""""""""""""""""""""""""""

This is the default path on all the build farm hosts that bitstream builds will run out of.

``build_farm_hosts``
"""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""

This is a sequence of unique hostnames/IP addresses that should be used as build farm hosts.
Each build farm host (given by the unique hostname/IP address) can have an optional mapping that provides an
``override_build_dir`` that overrides the ``default_build_dir`` given just for that build farm host.

.. _bit-builder-recipe:

Bit Builder Recipes (``bit-builder-recipes/*``)
------------------------------------------------

Here is an example of this configuration file:

.. literalinclude:: /../deploy/bit-builder-recipes/f1.yaml
   :language: yaml

``bit_builder_type``
^^^^^^^^^^^^^^^^^^^^

This key/value specifies a bit builder class to use for building bitstreams.
By default, bit builder classes can be found in :gh-file-ref:`deploy/buildtools/bitbuilder.py`. However, you can specify
your own custom bit builder classes by adding your python file to the ``PYTHONPATH``.
For example, to use the ``F1BitBuilder`` build farm class, you would write ``bit_builder_type: F1BitBuilder``.

``args``
^^^^^^^^^^^^^^^^^^^^^

This section specifies all arguments needed for the specific ``bit_builder_type`` used.
For a list of arguments needed for a bit builder class, users should refer to
the ``_parse_args`` function in the bit builder class given by ``bit_builder_type``.

``f1.yaml`` bit builder recipe
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This bit builder recipe configures a build farm host to build an AWS EC2 F1 AGFI (FPGA bitstream).

Here is an example of this configuration file:

.. literalinclude:: /../deploy/bit-builder-recipes/f1.yaml
   :language: yaml

``s3_bucket_name``
""""""""""""""""""""""""""

This is used behind the scenes in the AGFI creation process. You will only
ever need to access this bucket manually if there is a failure in AGFI creation
in Amazon's backend.

Naming rules: this must be all lowercase and you should stick to letters and numbers ([a-z0-9]).

The first time you try to run a build, the FireSim manager will try to create
the bucket you name here. If the name is unavailable, it will complain and you
will need to change this name. Once you choose a working name, you should never
need to change it.

In general, ``firesim-yournamehere`` is a good choice.

``append_userid_region``
""""""""""""""""""""""""""

When enabled, this appends the current users AWS user ID and region to the ``s3_bucket_name``.

``vitis.yaml`` bit builder recipe
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This bit builder recipe configures a build farm host to build an Vitis U250 (FPGA bitstream called an ``xclbin``).

``device``
""""""""""""""""""""""""""
This specifies a Vitis platform to compile against, for example: ``xilinx_u250_gen3x16_xdma_3_1_202020_1``.

Here is an example of this configuration file:

.. literalinclude:: /../deploy/bit-builder-recipes/vitis.yaml
   :language: yaml
