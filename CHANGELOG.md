# Changelog

This changelog follows the format defined here: https://keepachangelog.com/en/1.0.0/
Versioning follows semantic versioning as described here: https://semver.org/spec/v2.0.0.html

## [1.12.1] - 2022-01-21
This maintenence release mostly fixes bugs and improves some under-the-covers
behaviors.

### Added

### Fixed
* PR #200 makes disk mounting/unmounting more reliable
* PRs #207 and #222 change how build parallelism is handled. jLevels now
  default to the number of available cores and buildroot uses the user-provided
  jlevel.
* PR #224 fixes hard-coded disk images. These seem to have stopped working
  properly at some point. Incidentally, this also allows bare-metal workloads
  to specify a hard-coded disk image that will be installed to FireSim but does
  nothing in functional simulation.

### Changed
* PR #203 switches to a more recent and minimal fedora base image
* PR #211 Changes the configuration loading process to only attempt to load
  workloads that were actually required. Previously, FireMarshal would load all
  configs in its path, even if it wouldn't use them.

## [1.12.0] - 2021-04-11
This is a fairly small release that adds initial support for a chip tapeout
prototyping board, yaml support, and a few improvements to the buildroot distro
options. This release coordinates with Chipyard release 1.5.0.

### Added
* PR #186 adds support for yaml workload configuration files. So far, we do not
  use any yaml features other than comments.
* PR #190 adds a new board for chip prototyping

### Changed
* PR #187 adds performance counters to the Linux configuration for Fedora
* PR #191 and #193 improve handling of environment variable in buildroot
  configuration. Shell variables will now be expanded by FireMarshal and
variables will be available at all steps in the buildroot build process.

### Fixed
* PR #189 fixes a bug where workloads that didn't include any kernel modules
  would not build.

## [1.11.0] - 2021-01-12
This release coordinates with Chipyard v1.4.0 and includes several
board-related features, improving customizability of the hard-coded base
components. Another important change to note is the inclusion of an updated
default icenet driver that requires a recent version of that device in
RocketChip (see details below). Finally, the Fedora distribution has been
updated to a more recent version.

### Added
* PR #174 adds the ability to run multiple jobs from a single call to marshal.
  This is useful for testing multi-node workloads, although we still lack a
  simulated network.
* PR #181 modularizes simulator integration (the 'install' command). We can now
  add custom installation targets as part of the board specification.
* PR #182 Allows users to customize the distro for their workload. This is
  particularly important for buildroot which has many options that are hard to
  modify in a child workload. Workloads that based directly on a distro (rather
  than one of the board's base workloads like 'br-base.json') will need to be
  updated to use the new 'distro' option, users are discouraged from doing this.
  Along with the new 'distro' workload option, board/distro handling was
  overhauled significantly internally. The result of this is that boards (the
  bottom of the inheritance tree along with all the basic default components) are
  much more self-contained and it's easier to specify new ones.

### Changed
* PR #175 updates the Fedora distro base image to Fedora 31
* PR #177 allows you to explicitly disable device drivers in parent workloads
  (e.g. to disable the icenet driver in br-base).
* PR #178 adds warning messages when workloads include unrecognized options.
  This helps avoid subtle bugs in workloads.
* PR #180 updates the default icenet driver to support changes in the icenet
  device. These changes are not backwards compatible, this FireMarshal release
  is coordinated with Chipyard 1.4.0 to ensure compatiblity. The specific version
  was introduced in https://github.com/firesim/icenet-driver/pull/3. However,
  users may still explicitly provide the old driver in their workload description
  using the linux/modules option.

### Fixed


## [1.10.0] - 2020-10-05
The biggest change in this release is the introduction of OpenSBI as the
default firmware. BBL is still supported, but no longer the default. Other
changes include a number of performance improvements, better support for
user-provided kernel modules, and various bug fixes.

### Added
* PR #165 adds the ability to include user-provided kernel modules. These are
  automatically built and loaded as opposed to the 'post-bin' option which is
  more flexible but can't load in the early boot and is more work. This also
  unifies all linux-related options into their own 'linux' option. The old
  'linux-src' 'linux-config' options are still supported but deprecated, users
  should now specify those in the 'linux' option. 
* Firmware Improvements, OpenSBI support
    * PR #152 adds support for OpenSBI as the default firmware. BBL is still
      supported, but is no longer the default (use the "use-bbl" option).
    * PR #172 Adds 'opensbi-build-args' and 'bbl-build-args' options. It also
      introduces the new 'firmware' option group (deprecating the 'pk-src'
      option and grouping all firmware-relevant options into one place).

### Changed
* PR #156 patches the default kernel to enable RoCC instructions by default.
  This is a common need and has minimal risk to non-RoCC platforms.
* Performance Improvements
    * PR #159 moves submodule status checking to only run for the workload
      being built (rather than every workload in the workload search path).
      Before this, if you had many workloads in your project, marshal could spend a
      lot of time checking the same modules over and over.
    * PR #166 Copies the parent's binary instead of rebuilding for every child
      if none of the binary-related options changed. This is a big performance
      improvement for applications with many child workloads.
* PR #162 overhauls the unit testing framework. Previous versions were hard to
  maintain and had various bugs that could mask failures from time to time.
  There is now a standard way to write complex tests and all unit tests were
  confirmed to pass.

### Fixed
* Better support for files generated by host-init
    * PR #158 correctly detects changes in files/overlay generated in host-init
      (bug #157).
    * PR #167 Handles kernel modules generated in host-init
* PR #160 broadens the scope of up to date criteria to include file metadata.
  For example, adding execute permissions to a file in file/overlay previously
  failed to trigger a rebuilt (bug #145)
* PR #162 ignores symlinks in overlays which often won't work if the symlink
  points to files in the rootfs rather than the overlay.
* PR #164 fixes bug #163. A sufficiently large initramfs could overwrite kernel
  memory. This tended to break Fedora --no-disk builds.

## [1.9.0] - 2020-05-21
This is largely a maintenance release with a few minor features and a bunch of
bug fixes. The most significant change is a bump to Linux 5.7rc3. The new
'firesim-dir' configuration option is also signficant because it enables more
flexible deployment of FireMarshal in Chipyard and FireSim and is required for
Chipyard 1.3.0.

### Added
* A number of new global configuration options were added. This includes
  specifying where the 'install' command finds firesim (PR #127) and allowing
  for custom workload searchpaths (PR #140)
* PR #129 adds a number of new features to help support profiling and debug. In
  particular, Marshal now saves the raw kernel ELF file including debug symbols
  (BBL strips DWARF info). It also handles a few handy scripts for running
  FirePerf tools.
* PR #141 adds the 'post-bin' script option to workloads. This is primarily
  useful for building custom drivers but may have other uses.

### Changed
* PR #139 enables RVC in the kernel by default
* PR #143 changes the behavior of kernel fragments. Previously, workload kfrags
  were applied directly on top of the distro's default config. Now kfrags are
  inherited through the chain of parents.
* PR #151 bumps us to Linux 5.7rc3
* PR #148 switches to the SBI serial driver from the sifive uart due to a
  baud-rate configuration issue. This will be reversed eventually.

### Fixed
* PR #126 adds detection of changes in overlay directories that were missed before
* PR #128 fixes an issue when calling FireMarshal inside a makefile
* PR #133 fixes the --workdir option that had broken (and adds tests so it won't happen again)
* PR #135 jobs were not including device drivers

## [1.8.0] - 2020-01-24
This release introduces user-configurable options for FireMarshal through a
config file or environment variables. It also relaxes constraints on the
location of the workload directories (they can be anywhere in your filesystem
now). These changes make it much easier to add the marshal command to your PATH
and use it like a general utility.

### Added
* PR #81 Allows for multiple workload directories, and for those directories to be anywhere in the filesystem.
* PR #93 Enables basic functionality without initializing all submodules. Submodules are only accessed if they are actually used. Also introduces the init-submodules.sh script.
* PR #94 Adds the 'rootfs-size' option to allow for custom rootfs sizes. Also defaults to a tight-packing of the rootfs by default, greatly reducing default image sizes.
* PR #96 Adds options for machine configuration at launch time (memory and cpu amounts)
* PR #107 Adds a configuration system for common tool options (like default output locations or search paths). This will enable much more flexibility in the future.
* PR #116 and #117 allow for more customization in build components (BBL and Qemu sources, respectively)

### Changed
* PR #83 changes the way buildroot dependencies are checked. We now only rebuild buildroot if the submodule version changes (or if it's dirty) or if the buildroot configuration changes. This drastically reduces build times.
* PR #84 cleans up much of the logging output for the 'test' command
* PR #118 changes the default location of the build database (doit.db) and allows it to be configured.

### Fixed
* PR #101 switches to a github mirror for busybox from their private repo which was experiencing frequent server failures.
* Many documentation fixes and updates (including centos-requirements.txt, contributing guidelines, and tutorials). In particular, PR #121 expands the tutorial section.
 
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
