# FireMarshal

FireMarshal is a workload generation tool for RISC-V based systems that automates constructing boot binaries and filesystem images and their evaluation.

## Quick Links

* **Stable Documentation**: https://firemarshal.readthedocs.io/en/latest/index.html
* **Paper Publication**: https://ieeexplore.ieee.org/document/9408192
* **Bugs and Feature Requests**: https://github.com/firesim/FireMarshal/issues
* **General Questions, Help, Discussion**: https://groups.google.com/forum/#!forum/firesim
* **Want to contribute?**: https://github.com/firesim/FireMarshal/blob/master/CONTRIBUTING.md

## Setup

### *RECOMMENDED* Chipyard/FireSim Integration

The easiest way to use FireMarshal is to run it via [Chipyard](https://chipyard.readthedocs.io/en/latest/) or [FireSim](https://docs.fires.im/en/latest/).
However, this is not required.
To run FireMarshal independently, follow along from the next section (Standalone setup).

### Standalone Setup

FireMarshal uses the [Conda](https://docs.conda.io/en/latest/) package manager to help manage system dependencies.
This allows users to create an "environment" that holds system dependencies like ``make``, ``git``, etc.
Please install Conda using the Chipyard documentation [here](https://chipyard.readthedocs.io/en/main/Chipyard-Basics/Initial-Repo-Setup.html#default-requirements-installation).

Next you can run the following command to create a FireMarshal environment called ``firemarshal``.

```bash
conda env create -f ./conda-reqs.yaml -n firemarshal
```

To enter this environment, you then run the ``activate`` command.
**Note that this command should be run whenever you want to use FireMarshal so that packages can be properly be added to your ``PATH``**.

```bash
conda activate firemarshal # or whatever name you gave during environment creation
```

In addition to standard packages added in the conda environment, you will need a RISC-V compatible toolchain and the RISC-V ISA simulator (Spike).
A RISC-V compatible toolchain can be obtained by the following:

```bash
conda install -n firemarshal -c ucb-bar riscv-tools
```

To install Spike, please refer to https://github.com/riscv-software-src/riscv-isa-sim.

Finally, if you are running as a user on a machine without ``sudo`` access it is required for you to install ``guestmount`` for disk manipulation.
You can install this through your default package manager (for ex. ``apt`` or ``yum``).

## Basic Usage

If you only want to build bare-metal workloads, you can skip updating submodules.
Otherwise, you should update the required submodules by running:

```bash
./init-submodules.sh
```

Building workloads:

```bash
./marshal build br-base.json
```

To run in qemu:

```bash
./marshal launch br-base.json
```

To install into FireSim (assuming FireMarshal is setup within a Chipyard/FireSim installation):

```bash
./marshal install br-base.json
```

## Security Note

Be advised that FireMarshal will run initialization scripts provided by workloads.
These scripts will have all the permissions your user has, be sure to read all workloads carefully before building them.

## Releases

The master branch of this project contains the latest unstable version of FireMarshal.
It should generally work correctly, but it may contain bugs or other inconsistencies from time to time.
For stable releases, see the release git tags or GitHub releases page.
