Contributing to FireMarshal
=============================

## Contributing Changes
We welcome contributions to FireMarshal through [github
PRs](https://github.com/firesim/FireMarshal/pulls). **All PRs should be against
the master branch**.

Before submitting a PR:
* Merge origin/master to ensure you have the latest changes.
* Run ./fullTest.py to ensure the basic unit-tests still work
* Ensure all code passes lint (this will be enforced by github). You can
  manually check each file (or a glob) with the following command:

    pylama --ignore="E501" --linters="pycodestyle,pyflakes" FILENAME.py

## Getting Help / Discussion:
* For general questions, help, and discussion: use the FireSim user forum: https://groups.google.com/forum/#!forum/firesim
* For bugs and feature requests: use the github issue tracker: https://github.com/firesim/FireMarshal/issues

## Branch management:
1) **tagged releases**: All stable releases are tagged and tracked with githubs releases mechanism.
1) **master**: Unstable development. Master should work at all times, but there are no guarantees that bugs haven't been introduced.
2) **All other branches**: Private development branches, typically not suitable for use.
