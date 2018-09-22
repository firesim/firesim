# Changelog

This changelog follows the format defined here: https://keepachangelog.com/en/1.0.0/

## [1.3.2] - 2018-09-22

### Changed

* Serial IO model made deterministic (resolves https://github.com/ucb-bar/midas/issues/78 )
* `firesim managerinit` generates an initial bucket name that won't collide with an existing one
* verilator is now installed by the machine launch script 
* Rebuild EDMA driver on Run Farm nodes. This fixes a potential kernel version mismatch issue due to AWS GUI scripts

### Added

* Auto-ILA added: Annotate Chisel code to automatically wire-up an ILA
  * Enables ILA-based debugging of the FPGA simulation
  * Automatic generation and wiring of FPGA ILA, based on chisel annotations in relevant source code (target source code or simulation source code). This is done using the ILATopWiring transformation. Generates several partial Verilog files which are included in the top-level cl_firesim.sv file.
  * Includes refactoring of midas to allow post-midas host-transformations (in addition to the already existing target transformations)
  * Documentation and integration into manager for ease of use
* sim/Make system fractured into:
  * `sim/Makefile` -- the top-level Makefile
  * `sim/Makefrag` -- target-agnostic recipes for build simulators and simulation drivers and 
  * `sim/src/main/makefrag/<project>/Makefrag` -- target specific recipes for generating RTL
  * this makes it easier to submodule firesim from a larger project, and allows for multiple target projects to coexist within FireSim
  * see `Targets/Generating Different Target-RTL` in Advanced Docs. 
* MIDAS-examples added (Resolves https://github.com/firesim/firesim/issues/81 )
  * live in `sim/src/main/{cc, scala, makefrag}/midas-examples`
  * a suite of simple circuits like GCD to demonstrate MIDAS/FireSim
  * these serve as good smoke tests for bringing up features in MIDAS
  * see `Targets/Midas Examples` in Advanced Docs. 
* Scalatests updated
  * generates all of the MIDAS-examples, a Rocket- and Boom-based target and runs them through midas-level simulation.
    * good regression test for bumping/changing chisel/firrtl/rocket chip/midas
* Better ctags support. Script to generate ctags efficiently in `gen-tags.sh`. Also called by build-setup process. On a fresh clone, gen-tags.sh only takes ~10s. Resolves #79 
  * Generated across: target-design code, all shim code, driver code, workloads, etc.


## [1.3.1] - 2018-08-18

### Changed

* Update version of BOOM included as a target in FireSim. The included version/configurations now reliably boot Linux and run SPEC. The FireSim NIC/network is also supported.
* Update IP addressing for simulated nodes to prevent a conflict between host IP range and simulation IP range. Simulations now live in 172.16.0.0/16 instead of 192.168.0.0/24.
* Update experimental SSH into target instructions to account for different interface names across EC2 instance types. This fixes the ability to access the internet from within simulated nodes.


## [1.3.0] - 2018-08-11

### Changed

* **IMPORTANT**: `aws-fpga-firesim` is updated to the upstream [`aws-fpga` 1.4.0 Shell release](https://github.com/aws/aws-fpga/releases/tag/v1.4.0). This is a **REQUIRED** update for all users, as AWS will be removing support for old shells after September 1st, 2018. See this release note from AWS: https://github.com/aws/aws-fpga/blob/master/RELEASE_NOTES.md#release-140-see-errata-for-unsupported-features. In addition to pulling in the changes to the FireSim codebase for this release, you will need to launch a new manager instance using Amazon FPGA Developer AMI version 1.4.0. The documentation is updated to reflect this.
* For users that cannot update directly to FireSim 1.3.0 because they cannot switch to the version of Rocket Chip updated in FireSim 1.2.0, a backport branch that adds the AWS FPGA 1.4.0 Shell to FireSim 1.1 is provided here: https://github.com/firesim/firesim/tree/firesim-1.1-aws-1.4.0-backport. In addition to pulling in the changes to the FireSim codebase for this branch, you will need to launch a new manager instance using Amazon FPGA Developer AMI version 1.4.0. The documentation is updated to reflect this.

### Added

* A new flag now allows zeroing FPGA-attached DRAM before simulation. This is enabled by default in the manager.
* New docs that explain how to `ssh` into simulated nodes.


## [1.2.0] - 2018-07-14

### Added

* FireSim now has beta support for simulating [BOOM](https://github.com/ucb-bar/riscv-boom)-based nodes. BOOM is the Berkeley Out-of-Order Machine, a superscalar out-of-order RISC-V Core. See the following page for usage instructions: https://docs.fires.im/en/1.2/Advanced-Usage/Generating-Different-Targets.html#boom-based-socs-beta

### Changed

* Rocket Chip has now been bumped to a version from April 23, 2018.


## [1.1.0] - 2018-05-21

### Added

Our first release of FireSim! Everything is a newly added feature, so check out
the full documentation in the docs directory or the docs tagged 1.1.0 at 
https://docs.fires.im .


## [1.0.0] - 2017-08-29

### Added

This was a closed-source, but free-to-use demo of FireSim released on
[AWS Marketplace](https://aws.amazon.com/marketplace/pp/B0758SR46G) with our 
[AWS Compute Blog Post](https://aws.amazon.com/blogs/compute/bringing-datacenter-scale-hardware-software-co-design-to-the-cloud-with-firesim-and-amazon-ec2-f1-instances/)
in August 2017. This was built from a very old version of FireSim and no
longer supported, but noted here to explain why the versioning of this repo
starts at 1.1.0.
