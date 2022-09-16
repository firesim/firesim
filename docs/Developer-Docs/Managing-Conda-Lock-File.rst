Managing the Conda Lock File
------------------------------

The default conda environment set by ``build-setup.sh`` uses the `lock file ("*.conda-lock.yml") <https://github.com/conda-incubator/conda-lock>`_ at the top of the repository.
This file is derived from the normal conda requirements file (``*.yaml``) also located at the top-level of the repository.

Updating Conda Requirements
===========================

If developers want to update the requirements file, they should also update the lock file accordingly.
There are two different methods:

#. Running ``build-setup.sh --unpinned-deps``. This will update the lock file in place so that it can be committed and will re-setup the FireSim repository.
#. Manually running ``conda-lock -f <Conda requirements file> -p linux-64 --lockfile <Conda lock file>``

Caveats of the Conda Lock File and CI
=====================================

Unfortunately, so far as we know, there is no way to derive the conda requirements file from the conda lock file.
Thus, there is no way to verify that a lock file satisfies a set of requirements given by a requirements file.
It is recommended that anytime you update the requirements file, you update the lock file in the same PR.
This check is what the ``check-conda-lock-modified`` CI job does.
It doesn't check that the lock file and requirements file have the same packages and versions, it only checks that both files are modified in the PR.
