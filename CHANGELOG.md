# Changelog

This changelog follows the format defined here: https://keepachangelog.com/en/1.0.0/

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
