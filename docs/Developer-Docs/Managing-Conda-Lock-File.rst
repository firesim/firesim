Managing the Conda Lock File
------------------------------

The default Conda environment set by ``build-setup.sh`` uses the `lock file ("*.conda-lock.yml") <https://github.com/conda-incubator/conda-lock>`_ in ``conda-reqs/*``.
This file is derived from the Conda requirements files (``*.yaml``) also located at ``conda-reqs/*``.

Updating Conda Requirements
===========================

If developers want to update the requirements files, they should also update the lock file accordingly.
There are two different methods:

#. Running ``build-setup.sh --unpinned-deps``. This will update the lock file in place so that it can be committed and will re-setup the FireSim repository.
#. Running :gh-file-ref:`scripts/generate-conda-lockfile.sh`. This will update the lock file in place without setting up your directory.

Caveats of the Conda Lock File and CI
=====================================

Unfortunately, so far as we know, there is no way to derive the Conda requirements files from the Conda lock file.
Thus, there is no way to verify that a lock file satisfies a set of requirements given by a requirements file(s).
It is recommended that anytime you update a requirements file, you update the lock file in the same PR.
This check is what the ``check-conda-lock-modified`` CI job does.
It doesn't check that the lock file and requirements files have the same packages and versions, it only checks that all files are modified in the PR.
