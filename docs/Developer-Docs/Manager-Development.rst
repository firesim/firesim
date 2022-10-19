Manager Development
=======================================================

Writing PyTests
+++++++++++++++++

PyTests for the FireSim manager are located in :gh-file-ref:`deploy/tests`.
To write a PyTest, please refer to https://docs.pytest.org/en/7.1.x/.

Running PyTests Locally
+++++++++++++++++++++++

Assuming the FireSim repository is setup properly, PyTests can be run by doing the following:

::

    cd <FireSim Root>
    cd deploy/
    pytest

By default this will run all PyTests.

Adding PyTests To CI
+++++++++++++++++++++++

By default all PyTests are run by CI using the same command shown in the prior section.
This can be seen in https://github.com/firesim/firesim/blob/d16969b984df6d0cb5cd3e8ed27d89d03095a180/.github/workflows/firesim-run-tests.yml#L147-L156 and :gh-file-ref:`.github/scripts/run-manager-pytests.py`.
