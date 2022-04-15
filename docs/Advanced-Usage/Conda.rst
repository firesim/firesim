Non-Source Dependency Management
================================

In :doc:`/Initial-Setup/Setting-up-your-Manager-Instance`, we quickly copy-pasted the contents
of ``scripts/machine-launch-script.sh`` into the EC2 Management Console and
that script installed many dependencies that FireSim needs using
`conda <https://conda.io/en/latest/index.html>`_,  a platform-agnostic package
manager, specifically using packages from the `conda-forge community <https://conda-forge.org/#about>`_.

In many situations, you may not need to know anything about ``conda``.  By default, the
``machine-launch-script.sh`` installs ``conda`` into ``/opt/conda`` and all of the FireSim dependencies into
a 'named environment' ``firesim`` at ``/opt/conda/envs/firesim``.
``machine-launch-setup.sh`` also adds the required setup to the system-wide ``/etc/profile.d/conda.sh`` init script to add
``/opt/conda/envs/firesim/bin`` to everyone's path.

However, the script is also flexible.  For example, if you do not have root access, you can specify
an alternate install location with the ``--prefix`` option to ``machine-launch-script.sh``.  The only requirement
is that you are able to write into the install location.  See ``machine-launch-script.sh --help`` for more details.

.. warning::

    To :ref:`run a simulation on a F1 FPGA <running_simulations>` , FireSim currently requires that
    you are able to act as root via ``sudo``.

    However, you can do many things without having root, like :doc:`/Building-a-FireSim-AFI`,
    `<meta-simulation>`_ of a FireSim system using Verilator or even developing new features in FireSim.

Updating a Package Version
--------------------------

If you need a newer version of package, the most expedient method to see whether there
is a newer version available on `conda-forge`_ is to run ``conda update <package-name>``.  If you are lucky,
and the dependencies of the package you want to update are simple, you'll see output that looks something like
this ::

    bash-4.2$ conda update moto
    Collecting package metadata (current_repodata.json): done
    Solving environment: done

    ## Package Plan ##

      environment location: /opt/conda

      added / updated specs:
        - moto


    The following NEW packages will be INSTALLED:

      graphql-core       conda-forge/noarch::graphql-core-3.2.0-pyhd8ed1ab_0

    The following packages will be UPDATED:

      moto                                  2.2.19-pyhd8ed1ab_0 --> 3.1.0-pyhd8ed1ab_0

    Proceed ([y]/n)?


The addition of ``graphql-core`` makes sense because the `diff of moto's setup.py between
2.2.19 and 3.1.0 <https://github.com/spulec/moto/compare/2.2.19...3.1.0#diff-60f61ab7a8d1910d86d9fda2261620314edcae5894d5aaa236b821c7256badd7>`_
shows it was clearly added as a new dependence.

And this output tells us that latest version of ``moto`` available is 3.1.0.  Now, you might be tempted to
hit ``<<Enter>>`` and move forward with your life.

.. attention::

    However, it is always a better idea to modify the version in ``machine-launch-script.sh`` so that:
    #. you remember to commit and share the new version requirement.
    #. you are providing a complete set of requirements for ``conda`` to solve.  There is a subtle difference between installing everything you need in a single `conda install` vs incrementally installing one or two packages at a time because  the version constraints *are not maintained between conda invocations*.   (NOTE: certain packages like Python are implicitly `pinned <https://docs.conda.io/projects/conda/en/latest/user-guide/tasks/manage-pkgs.html#preventing-packages-from-updating-pinning>`_ at environment creation and will `only be updated if explicitly requested <https://docs.conda.io/projects/conda/en/latest/user-guide/tasks/manage-python.html#updating-or-upgrading-python>`_ .) 


So, modify ``machine-launch-script.sh`` with the updated version of ``moto``, and run it.  If you'd like to see what
``machine-launch-script.sh`` will do before actually making changes to your environment, feel free to give it the ``--dry-run``
option, look at the output and then run again without ``--dry-run``.

In this case, when you are finished, you can run ``conda list --revisions`` and you should see output
like the following ::

    bash-4.2$ conda list --revisions
    2022-03-15 19:21:10  (rev 0)
    +_libgcc_mutex-0.1 (conda-forge/linux-64)
    +_openmp_mutex-4.5 (conda-forge/linux-64)
    +_sysroot_linux-64_curr_repodata_hack-3 (conda-forge/noarch)
    +alsa-lib-1.2.3 (conda-forge/linux-64)
    +appdirs-1.4.4 (conda-forge/noarch)
    +argcomplete-1.12.3 (conda-forge/noarch)

     ...   many packages elided for this example ...

    +xxhash-0.8.0 (conda-forge/linux-64)
    +xz-5.2.5 (conda-forge/linux-64)
    +yaml-0.2.5 (conda-forge/linux-64)
    +zipp-3.7.0 (conda-forge/noarch)
    +zlib-1.2.11 (conda-forge/linux-64)
    +zstd-1.5.2 (conda-forge/linux-64)

    2022-03-15 19:34:06  (rev 1)
         moto  {2.2.19 (conda-forge/noarch) -> 3.1.0 (conda-forge/noarch)}

This shows you that the first time ``machine-launch-script.sh`` was run, it created 'revision' 0 of the environment with
many packages.  After updating the version of ``moto`` and rerunning, 'revision' 1 was created by updating the version
of ``moto``.  At any time, you can revert your conda environment back to an older 'revision' using ``conda install -revision <n>``

Multiple Environments
---------------------

In the example above, we only wanted to update a single package and it was fairly straightforward -- it only updated
that package and installed a new dependency.  However, what if we're making a larger change and we think we might
need to have both sets of tools around for awhile?

In this case, make use of the ``--env <name>`` option of ``machine-launch-script.sh``.  By giving a descriptive
name with that option, you will create another 'environment'.  You can see a listing of available environments
by running ``conda env list`` to get output similar to::

    bash-4.2$   conda env list
    # conda environments:
    #
    base                     /opt/conda
    firesim                  /opt/conda/envs/firesim
    doc_writing           *  /opt/conda/envs/doc_writing

In the output above, you can see that I had the 'base' environment that is created when you install ``conda`` as well as
the ``firesim`` environment that ``machine-launch-script.sh`` creates by default.  I also created a 'doc_writing' environment 
to show some of the examples pasted earlier. 

You can also see that 'doc_writing' has an asterisk next to it, indicating that it is the currently 'activated' environment.
To switch to a different environment, I could ``conda activate <name>`` e.g. ``conda activate firesim``

By default, ``machine-launch-script.sh`` installs the requirements into 'firesim' and runs ``conda init`` to ensure that the
'firesim' environment is activated at login.

.. attention

    When you create additional environments by rerunning ``machine-launch-script.sh`` and providing
    ``--env <name>`` the environment activated at login does not get updated.  You can always check
    the currently activated environment by looking at the output of ``conda env list`` (as above) or
    ``conda info``.

Adding a New Dependency
-----------------------

Look for what you need in this order:

#. `The existing conda-forge packages list <feedstock-list>`_.  Keep in mind that since ``conda`` spans several domains, the
   package name may not be exactly the same as a name from PyPI or one of the system package managers.
#. `Adding a conda-forge recipe <https://conda-forge.org/#add_recipe>`_. If you do this, let the firesim@googlegroups.com
   mailing list know so that we can help get the addition merged.
#. `PyPI <https://pypi.org/>`_ (for Python packages).  While it is possible to install packages with pip into a ``conda``
   environment, `there are caveats <https://docs.conda.io/projects/conda/en/latest/user-guide/tasks/manage-environments.html?highlight=pip#using-pip-in-an-environment>`_.
   In short, you're less likely to create a mess if you use only conda to manage the requirements and dependencies
   in your environment.
#. System packages as a last resort.  It's very difficult to have the same tools on different platforms when they are being
   built and shipped by different systems and organizations.  That being said, in a pinch, you can find a section for
   platform-specific setup in ``machine-launch-script.sh``.
#. As a *super* last resort, add code to ``machine-launch-script.sh`` or ``build-setup.sh`` that installs whatever you need
   and during your PR, we'll help you migrate to one of the other options above.

Building From Source
--------------------

If you find that a package is missing an optional feature, consider looking up it's 'feedstock' (aka recipe) repo in
`The existing conda-forge packages list <feedstock-list>`_.  and submitting an issue or PR to the 'feedstock' repo.

If you instead need to enable debugging or possibly actively hack on the source of a package:

#. Find the feedstock repo in the `feedstock-list`_
#. Clone the feedstock repo and modify ``recipe/build.sh`` (or ``recipe/meta.yaml`` if there isn't a build script)
#. ``python build-locally.py`` to `build using the conda-forge docker container <https://conda-forge.org/docs/maintainer/updating_pkgs.html#testing-changes-locally>`_
   If the build is successful, you will have an installable ``conda`` package in ``build_artifacts/linux-64`` that can be
   installed using ``conda install -c ./build_artifacts <packagename>``.  If the build is not successful, you can
   add the ``--debug`` switch to ``python build-locally.py`` and that will drop you into an interactive shell in the
   container.  To find the build directory and activate the correct environment, just follow the instructions from
   the message that looks like::

    ################################################################################
    Build and/or host environments created for debugging.  To enter a debugging environment:

    cd /Users/UserName/miniconda3/conda-bld/debug_1542385789430/work && source /Users/UserName/miniconda3/conda-bld/debug_1542385789430/work/build_env_setup.sh

    To run your build, you might want to start with running the conda_build.sh file.
    ################################################################################

If you are developing a Python package, it is usually easiest to install all dependencies using ``conda`` and then install
your package in 'development mode' using ``pip install -e <path to clone>`` (and making sure that you are using ``pip`` from your environment).

Running conda with sudo
-----------------------

``tl;dr;`` run conda like this when using ``sudo``::

    sudo -E $CONDA_EXE <remaining options to conda>

If you look closely at ``machine-launch-script.sh``, you will notice that it always uses the full path
to ``$CONDA_EXE``.  This is because ``/etc/sudoers`` typically doesn't bless our custom install prefix of ``/opt/conda``
in the ``secure_path``.

You also probably want to include the ``-E`` option to ``sudo`` (or more specifically
``--preserve-env=CONDA_DEFAULT_ENV``) so that the default choice for the environment to modify
is preserved in the sudo environment.

Running things from your conda environment with sudo
----------------------------------------------------

If you are running other commands using sudo (perhaps to run something under gdb), remember, the ``secure_path``
does not include the conda environment by default and you will need to specify the full path to what you want to run,
or in some cases, it is easiest to wrap what you want to run in a full login shell invocation like::

   sudo /bin/bash -l -c "<command to run as root>"

The ``-l`` option to ``bash`` ensures that the **default** conda environment is fully activated.  In the rare case that
you are using a non-default named environment, you will want to activate it before running your command::

    sudo /bin/bash -l -c "conda activate <myenv> && <command to run as root>"


Additional Resources
--------------------
* `conda-forge`_
* `Conda Documentation <https://conda.io/projects/conda/en/latest/index.html>`_


.. _conda-forge: https://conda-forge.org
.. _feedstock-list: https://conda-forge.org/feedstock-outputs/
