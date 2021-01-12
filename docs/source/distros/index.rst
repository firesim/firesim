.. _distros:

Base Distributions
=============================
Distributions are the bottom-most workloads in the inheritance chain.
Distributions are implemented as a python package included with the board.
Users will rarely interact with distributions directly since distributions may
require significant and tricky modifications to work with the board. Instead,
they should inherit from the base workloads included with the board. The one
exception is through the :ref:`distro option <distro-configuration>` in your
workload that allows you to tweak distro-specific options (e.g. add/remove
packages).


.. toctree::
   :maxdepth: 1

   Buildroot
   Fedora
   Bare
