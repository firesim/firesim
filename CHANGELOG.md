# Changelog

This changelog follows the format defined here: https://keepachangelog.com/en/1.0.0/

## [1.5.0] - 2019-02-24

A more detailed account of everything included is included in the dev->master PR for this release: https://github.com/firesim/firesim/pull/168

### Added

* Supernode support now mainlined
    * Resolves #11 
    * Includes support for using all 4 host memory channels and connecting them to N targets
* FPGA Frequency now configurable in Chisel Config
* Printf Synthesis support. See Docs for more info.
* Generate elaboration artifacts
* Add ccbench workload
* Preliminary [GAP Benchmark Suite](https://github.com/sbeamer/gapbs) workload support
* PR #223. Adds post_build_hook, dumps `git diff --submodule` into build dir
* PR #225. Adds support for building `TARGET_PROJECT` =/= firesim (ex. midasexamples) in the manager
* PR #234. Adds support for f1.4xlarge instances
* PR #231. fasedtests as a `TARGET_PROJECT` for testing memory models & backing host memory sys.
* PR #212. New (alpha) workload generation system "FireMarshal" added

### Changed

* PR #218. Bump aws-fpga/FPGA Dev AMI support to 1.4.6 / 1.5.0 respectively.
    * Resolves #170 
    * According to AWS, this should still work for users on the 1.4.0 AMI
* Switch to XDMA from EDMA for DMA transfers. Improves performance ~20% in single-instance cases. 
    * Resolves #51
* Only request build-farm instances after successful replace-rtl
    * Resolves #100 
* SBT project reworked; FIRRTL provided as an unmanaged dep; target-land annotations pulled into separate project.
  * Resolves #175 
* Common DMA RTL factored out into Widget Traits in MIDAS
* Boom bumped with RVC Support
    * Resolves #202 
* PR #232. Adds separate optimization flags RTL-simulators/driver 

### Fixed

* Properly generate exit codes in the manager
    * Resolves #194 
* Catch build error on infrasetup and log it to file + advise the user to run make command manually
    * Resolves #69 
* Fix mem-model bug due to FRFCFS having an under-provisioned functional model
* PR #199. Targets with long names can now be killed automatically by firesim
    * Resolves #56 
* PR #193. Fedora networking now works in FireSim 
    * Address assignment fixed (gets assigned IP addresses in slot-order on firesim)
* PR #204. Fix support for heterogenous rootfs's - each job can have its own rootfs, or no rootfs at all

### Deprecated

* None

### Removed

* None

### Security

* None

## [1.4.0] - 2018-11-13

This is a large release. A much more detailed account of everything included is included in the PR: https://github.com/firesim/firesim/pull/114

### Changed

* Rocket Chip bumped to master as of September 26, 2018
* Reworked main loop in FPGA driver https://github.com/firesim/firesim/pull/98
* Start re-organizing firesim-software repo:
    * Commands for building base images have changed, see updated docs
    * Now supports building Fedora images, including initramfs images
    * Infrastructure prep for better workload generation/management system
    * Supports easily booting images in Spike/QEMU, with network support in QEMU for installing packages
* Better support for custom network topologies / topology mapping. Topologies can now provide their own custom mapping function. Support for topologies with multiple paths. Randomized switching across multiple paths.
    * Inline documentation as comments in user_topologies.py
* IceNIC Improvements:
    * NIC counts MMIO registered changed to make each count 8 bits instead of 4. This expands the maximum size of the req/resp queues from 16 to 256.
    * TX/RX completion interrupts separated into two different interrupts.
    * Added interrupt masking
* Misc Small Fixes
    * Better instance launch handling for spot instances
    * Fix ssh-agent handling; avoid forwarding because it breaks the workflow
    * Use async flags to speed up FPGA flashing / clearing
    * Fix L2 TLB & HPM counter configs for multi-core targets

### Added

* Block Device Model is now deterministic https://github.com/firesim/firesim/pull/106
* Endpoint clock-domain-crossing support https://github.com/firesim/firesim/pull/49
* Add synthesizeable unit tests from MIDAS
* Switch model token compression on empty batches of tokens to save BW when many links cross EC2 network. Does not compromise cycle-accuracy.
* **Debugging**: Assertion Synthesis
    * Assertions can now be synthesized and caught during FPGA-hosted simulation: http://docs.fires.im/en/latest/Advanced-Usage/Debugging/DESSERT.html
* **Debugging**: TracerV Widget
    * Widget for getting committed instruction trace from Rocket/BOOM to the host machine quickly using PCIS DMA
    * See documentation at: http://docs.fires.im/en/latest/Advanced-Usage/Debugging/TracerV.html
    * Also early support for multiple DMA endpoints (e.g. Tracer + NIC). Currently requires hardcoding endpoint addresses, will be addressed in next release.
* Infrastructure for merging supernode to master
    * Supernode currently lives on its own branch, will be merged in the future.
    * WIP on support for multiple copies of endpoints (e.g. multiple UARTs). 
    * Replace macro system for endpoints with generated structs.

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
