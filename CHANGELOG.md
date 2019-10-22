# Changelog

This changelog follows the format defined here: https://keepachangelog.com/en/1.0.0/

## [1.7.0] - 2019-10-12
This release primarily updates several FireMarshal defaults to recent
upstreamed versions, notably: Linux (v5.3), Fedora (20190126), and Buildroot (2019.08).

Also, offical documentation has moved to: https://firemarshal.readthedocs.io/en/latest/

### Added
* PR #47 enables custom arguments to spike and qemu (see also PR #48 that adds an example RoCC accelerator workload that uses these arguments)
* PR #61 includes an example sha3 workload (matches example RTL in chipyard)

### Changed
* PR #40 Enables alternative package manager mirrors in Fedora (if the default mirrors go down)
* PR #41 Switches the default Fedora image from a nightly build to a stable release.
* PR #44 Enables compressed riscv instructions (RVC) by default in Linux
* PR #46 Updates the default linux kernel to (mostly) upstream v5.3 from the previous non-standard fork of Linux
  * Introduces an initramfs by default in all builds that install drivers for iceblk and icenic
  * Workloads now use Linux kernel configuration fragments instead of full linux configurations (enables easy bumping of kernel versions)
* PR #50 updates buildroot to 2019.08 and switches to the github mirror
* PR #51 Moves marshal documentation to this repository (from FireSim). Docs now hosted at https://firemarshal.readthedocs.io/en/latest/.

### Fixed
* PR #57 fixes a bug where host-init scripts may not run in parent workloads unless they were built explicitly

## [1.6.0] - 2019-06-25
This release introduces a number of bug-fixes and support for standalone behavior (marshal can now be cloned by itself on a machine without sudo). It also synchronizes with firesim release 1.6.

### Added
* PR #31 allows firesim-software to work outside of a firesim environment without the need for sudo (just riscv-tools, qemu, and a few package requirements).
* PR #24 adds support for parallel 'launch' or 'test' commands.

### Changed
* PR #25 updates buildroot to a more recent version in order to support a more recent version of riscv-tools (as needed by firesim).
    * PR #32 finalizes the riscv-tools at gcc 7.2
* PR #22 enables multiple repository mirrors in the default fedora image

### Fixed
* PR #34 resolves #33 
* PR #29 fixes a number of issues with the full_test.sh script (bug #26 )
* PR #28 fixes an incompatibility with python36
    * Note that #31 officially updated the python version to 3.6, but firesim de-facto updated it in an earlier release.
