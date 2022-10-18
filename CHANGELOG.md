# Changelog

This changelog follows the format defined here: https://keepachangelog.com/en/1.0.0/

## [1.15.1] - 2022-10-18

Fixes to metasimulation, TracerV, and improved cross-platform support.

### Added
* sourceme-f1-manager.sh now has a --skip-ssh-setup argument for users who have pre-set ssh-agent config #1266

### Changed
* Instance liveness check now checks to see if login shell is reasonable #1266
* Driver/Metasim build at runtime now executed via run() to avoid conda warnings #1266
* Setup for QCOW2 on a run farm is only performed if the simulation needs it #1266
* The sim launch command is now written to a file before being executed for easier debugging. #1266

### Fixed
* Fix missing code in RuntimeBuildRecipeConfig that broke metasims #1266
* Hide warnings from sudo check, guestmount, etc. #1266
* Open file limit increased by default in machine-launch-script to work around buildroot bug. #1266
* TracerV: fix loop bounds in token processing #1249

## [1.15.0] - 2022-09-30

Full migration to Conda-based environment/dependency management; Chipyard now also uses Conda. Bump Rocket Chip/Chisel/etc. Various bugfixes/feature improvements.

### Added
* Refactor Conda + Bump Chipyard (which now uses Conda) #1206
* Support FPGA-managed AXI4/DMA in metasimulation #1191

### Changed
* Bump chipyard to 1.8.0 #1239
* Bump Rocketchip/chipyard/chisel #1216
* Metasimulation: remove dramsim2 and copy host memory model sources in-tree #1197
* Metasimulation: remove dependency on fesvr for ucontext #1196
* bridges: Remove references to DMA_X in driver sources #1184
* refactor most of machine-launch-script.sh into build-setup.sh #1180
* Backports go to stable branch, which should generally point to the la… #1176
* obey umask and default group in results-workload #1163
* Use libelf and libdwarf from conda #1160
* Improve fabric logging #1159
* Bump to Verilator 4.224 #1156
* ci: support running under forks of firesim #1144
* Allowed bridge parameters to be serialized #1141
* Don't use tsnyder conda channel in production machine-launch-script.sh #1121
* Make bug report system info copy pastable #1104

### Fixed
* manager: Cast AWS IDs to string in shareagfi #1227
* Stable backport of 1.12.1 AMI bump #1188
* Fix various VCS metasimulation breakages #1177
* Change elfutils submodule URL to HTTPS #1153
* Annotate Printf statements instead of intercepting parameters. #1151
* Deinit Chipyard's FireSim submodule under FireSim-as-top #1146
* add config_build_recipes.yaml to run_yamls pytest fixture #1143
* Fix mount files ownership #1137
* Add warn_only to vivado builds + Postpone error until all builds complete #1130
* Added missing return in tracerv_t::process_tokens to fix undefined behavior #1129
* correct doc for autocounter_csv_format #1126
* Fixing instructions for external SSH into simulation #1119
* docs: fix underlining in metasimulation configuration section #1106
* Fixed shebang in build-libdwarf.sh and build-libelf.sh scripts (copy #1101) #1105
* VitisShell: Use XPM xpm_cdc_sync_rst for reset synchronizer #1100

### Removed
* Removed the clock bridge annotation #1224
* Removed the in-memory bridge annotation #1223
* Removed the Fame1Instances transformation #1202

## [1.14.2] - 2022-08-30
Bump to use AWS FPGA Developer AMI 1.12.1 as 1.11.1 has been de-listed. This also bumps Vivado to 2021.2.

### Fixed
* Bump to use AWS FPGA Developer AMI 1.12.1
* Bump Vivado to 2021.2

## [1.14.1] - 2022-07-07
Adds firesim builddriver command, various bugfixes.

### Added
* New firesim builddriver command, which runs required driver/metasimulation builds without a launched run farm #1114
* Support for Sydney region on AWS #1111

### Changed
* Docs cleanup #1114 #1106
* Don't use tsnyder conda channel in production machine-launch-script.sh #1121

### Fixed
* Fixed documentation for SSH-ing into simulations of target designs with NICs #1119. Fixes #580.
* VitisShell: Use XPM xpm_cdc_sync_rst for reset synchronizer #1100
* Fix manager xclbin lookup bug during metasimulation #1114, https://groups.google.com/g/firesim/c/VxHX7QkKJCM

## [1.14.0] - 2022-06-18
Introduces support for local (on-premises) FPGAs and distributed metasimulation

### Added
*  Support for Vitis FPGAs #1087
*  Manager support for deploying verilator/vcs metasimulations, plusarg passthrough, and some useful DRYing-out #1076
*  ("Where to Run") Initial support running on different run farm hosts #1028
*  A host-portable AutoILA transform that instantiates the black box in IR #1059
*  Scala Source Formatting via Scalafmt #1060
*  VSCode Integration for Scala Development #1056
*  Support A Resource-Minimizing strategy ("AREA") for AWS-FPGA #1055
*  XDC-Driven Memory Hints for Xilinx FPGAs #1021
*  ("what-to-build") Modularize different run platforms (i.e. bitstream builds) #853
*  .ini to .yaml config files + supporting different build hosts #1006
*  Capture packet dump from switch #1011

### Changed
*  Cleanup config initialization #1082
*  Switch buildfarm API to be similar to runfarm API #1070
*  ("Where to Run") Initial support running on different run farm hosts #1028
*  Move C++ implementation of bridge streams out of bridge drivers #1017
*  awstools typing + small organization #1037
*  Collect Bridge Stream RTL Implementation under StreamEngine module #996
*  Use conda for distribution-agnostic dependency management #986
*  .ini to .yaml config files + supporting different build hosts #1006
*  Use FIRRTL 'FPGA backend' passes in the GG compiler + Isolate Emitter #981

### Fixed
*  Allow argument passing to bit builder #1046
*  Move sim. data class arg parsing into classes #1078
*  Hide blowfish deprecation warning until 2022-08-31 #1079
*  Have yes/no resolve to bool in Yaml #1069
*  Add bash-completion and install argcomplete global into it #1041
*  Fix CI FPGA sim timeout issue + Use Python3 formatting in run_linux_poweroff CI script #1040
*  Revert the change from #842 that makes launchrunfarm block on instances passing status checks #1003
*  Fix first clone setup fast script #990
*  Update libdwarf submodule url #988
*  Update test_amis.json #982

### Removed
*  Remove the data_t type alias + unused macros in generated header #1050
*  .ini to .yaml config files + supporting different build hosts #1006

## [1.13.6] - 2022-06-15
Last of the 1.13.x release series. CI fixes only, no user facing changes since 1.13.5

### Fixed
*  CI fixes (scala doc push) related to git version.

## [1.13.5] - 2022-06-13
Critical fix to git package version in machine-launch-script.sh, only required for newly launched manager instances.

### Fixed
*  Bump git version specified in machine-launch-script.sh from git224 (no longer available) to git236. #1081

## [1.13.4] - 2022-04-06
Critical fix to libdwarf submodule URL. Fix boto3 pagination in manager. Fix synth assert stop-printf pair detection.

### Fixed
*  update libdwarf submodule url #988
*  Fix synthesized assertions stop-printf pair detection #999 
*  Use pagination for boto3 calls in the manager #991 

## [1.13.3] - 2022-03-01
More small updates to AMI string in deploy area.

### Fixed
* Update AMI string in deploy to 1.11.1 #977

## [1.13.2] - 2022-02-28
More small clarifications to the documentation.

### Fixed
* Update AMI string in documentation to 1.11.1 #972 #973

## [1.13.1] - 2022-02-27
Small clarifications to the documentation and fixes the FPGA simulation driver initialization.

### Fixed
* Use `--skip-validate` in CI #957 #960
* Fix AWS FPGA init API (use `fpga_mpgmt_init`) #950
* Clarify AMI search term in documentation #967

## [1.13.0] - 2022-02-15
Highlights include a bump to Chisel 3.5 & FIRRTL 1.5, Vivado 2020.2 & Developer AMI 1.10, considerable FPGA QoR optimizations, and standardized file emission stategy from Golden Gate (all file names described [here](https://docs.fires.im/en/1.13.0/Golden-Gate/Output-Files.html)). 

### Added
* A Basic Floorplan for DRAM Controllers #798 
* A ResetPulseBridge to drive reset a conventional bridge #782
  * This is used in place of peek poke to avoid an early deadlock condition.
 * A global reset condition to mask off events during reset #791
* Support for XDC Emission that is Hierarchy-Mutation Robust. #825
* Multi-cycle constraints to improve fmax on multiclock designs. #834 
* Bake-in FASED default runtime configuration into hardware #889

### Changed
* Bumped to AMI 1.10 / AWS FPGA 1.4.19 / Vivado 2020.2 #788
* libelf + libdwarf now installed to a firesim-local sysroot @ sim/lib-install #806
* Improved host IFs to make it easier to define  bridges with custom channelization #778 
* Use a Standard File Emission Strategy #802
* Only use required DRAM channels to save FPGA resources #816 
* Limit builddir directory name length by omitting chisel_triplet #826
* install ca-certificates for latest root certs #840
* Removed -o and -E options in Golden Gate's CLI #851
* Changed word addresses to byte addresses in drivers #857  
* Provide a more informative env.sh on build-setup failure  #885 
* Use Published Dependencies For Chisel + FIRRTL #893 
* Factor peek/poke out of simif_t #864
* Chisel 3.5 / FIRRTL 1.5 Bump using Published Deps #899
* Allow launchrunfarm to retry up to a specified timeout #940

### Fixed
* Reject non-hardware types in calls to PerfCounter & FPGADebug #865
* FASED elaboration error in AXI4 width adapter when using all host DRAM  #881
* Driver Return Code #910

### Removed
* Source dependency on Barstools (ucb-bar/barstools) #803  
* Misc FASED Chisel Utilities + GeneratorUtils + PlusArgReader Pass #812
* FIRRTL IR node helpers in midas.passes.util #811 
* Boost Dependency #806
* WithAutoILA from default recipes #913

## [1.12.0] - 2021-06-14
Updates default AGFIs to fully utilize multiclock support, fixes a gnarly
FIRRTL deduplication interaction.

### Added
* Promote passthrough optimization (#707) to improve FMR in multi-model targets
* `firesim tar2afi --launchtime <time>` can be used to retry AGFI creation with the vivado design checkpoint tarball #683
* Multibit autocounter events (#706)
*  [ci] Add a script to build and push ci docker images #723
* AGFI ID printed into uartlog at simulation init time #755
* aws-fpga-firesim uses `-hierachical_percentages` on `report_utilization`, also `report_control_sets` post synthesis (firesim/aws-fpga-firesim#37)
* Begin adding pytest-driven unit tests for `firesim` manager script #754
* A strategy for disabling retiming firesim/aws-fpga-firesim#34

### Changed
* PLATFORM_CONFIG must mixin WithAutoILA to populate ILA #712 
* Check sns topic permission at start of buildafi and warn user #754
* Default AGFI target-frequency configurations #785
  *  Unnetworked targets (1.6 GHz, 0.8GHz, 1GHz) for Tile, Uncore, DRAM respectively
  *  Networked targets (3.2GHz , 3.2GHz, 1.0 GHz) for tile 
* Unrouted clock nets promoted to error firesim/aws-fpga-firesim#31

### Fixed
* [printf] Support %c format specifiers (#735, resolves #592)
* Fix VCS-related breakages in MIDASExamples, SynthUnittests #725
* Fix breakages related to new FIRRTL 1.4 DedupModules by limiting how many times it runs (#738, see #766)
* Replace DualQueue in the DRAM memory model scheduler with RRArbiter+Queue to prevent write starvation (#753)
* A bug that broke tracerV when using heterogeneous mixes of tiles #776

### Removed
* Coremark and SPEC workloads moved to Chipyard

## [1.11.0] - 2021-01-19
FireSim 1.11.0 formally introduces the _instance multithreading_ optimization, the subject of Albert Magyar's dissertation work, which can be used to improve FPGA capacity by up to 8X (2 -> 16 large boom cores) for some designs. Other notable changes include: putting DRAM in its own clock domain, RC + Chisel + FIRRTL bumps + many QoL improvements. 

### Added
* SBT 1.4 native client support; removes no SBT launch time (#668 )
* Extra queuing in the AssertBridge to improve fmax (#595)
* Platform config to enable model multi-threading resource optimization (#636)
* Support for designs with no AXI4 backing memory (#638)
* A simulation heartbeat to record throughput and detect simulation deadlock (#662)
* More informational logging during AutoCounter elaboration (#664) h/t @timsnyder-siv 
* Separate parameter to specify width of backendLatency in FASED (#663) h/t @timsnyder-siv 
* A 16-core LargeBOOM configuration has been added to FireChip (ucb-bar/chipyard#756)
* A switch to disable synth asserts at runtime (#619)

### Changed
* Bump to Chipyard 1.4 (Rocket Chip, Chisel 3.4.1, FIRRTL, 1.4.1)
* Default targets now put DRAM in it's own 1 GHz clock domain (#644)
* UARTBridge now obtains baud rate from UARTParams passed (#598) 
* Timing Failure and specific route_design failures promoted to fatal errors (firesim/aws-fpga-firesim#28)
* Make memory loading compatible with new testchipip API (#617)
* RationalClockBridge no longer assumes the zeroth clock is the base clock (#632)
* Bumped to Chisel 3.4 and FIRRTL 1.4 (#668)
* only warn on missing simoutputs copy back (#681) h/t @timsnyder-siv 
* repo_state_summary introspects toplevel git repo and cd's there (#682) h/t @timsnyder-siv 
* FIRRTL wiring transform is run in TargetTransforms by default (#625) 


### Fixed 
* An AssertionSynthesis where assertion causes would be misattributed  (#582) (BP to 5.10.1)
* Merging of memory channels under RAM optimizations (#589) (BP to 5.10.1)
* ILAWiring Transform scheduling to prevent it running after the emitter (#590, #594, #603) (BP to 5.10.1)
* Manager now terminates build instance and notifies user if Vivado fails during buildafi (#602)
* Ctrl bus can now address more than 1024 registers (#635) (BP to 5.10.1) h/t @timsnyder-siv 
* Model threading (#636) by non-power-of-two factors (#671)
* Can now specify custom runtime configs in relative paths above the default location (#665) h/t @timsnyder-siv 
* Quiet scalac warning about `midas.widgets.CppGenerationUtils.toStrLit` implicitConversion (#677) h/t @ingallsj
* Add try/catch blocks in toplevel driver functions to avoid VCS error DPI-UED (#684) h/t @timsnyder-siv 
* fix compile warning: Midas code unreachable (#679) h/t @ingallsj
* fix compile warnings: Midas Bits (#687) h/t @ingallsj
* provide vcs -top to avoid spurious toplevel in hierarchy (#690) h/t @timsnyder-siv 
* fix a bug where multiple BUFGCEs would be appended to generated verilog (#694)
* fix a deadlock in FASED instances with wide data interfaces (#680)

### Deprecated

### Removed

### Performance Enhancements
* When using model threading (#636), resource utilization of synchronous-read memories has improved (#639)

## [1.10.0] - 2020-05-31
Adds initial support for simulating multi-clock targets in FireSim.

### Added
* Support for simulating targets with multiple fixed-frequency clock (MC) domains (PR #441)
  * All clocks must be generated using the RationalClockBridge
  * See docs (PR #527)
* Generalized trigger system (part of MC, PR #441) (resolves #497)
  * See docs (PR #526)
* Intelligent Bridge DRAM allocation (PR #433)
  * Mix in `UsesHostDRAM` into a bridge that needs FPGA-DRAM
* CircleCI  integration (PR #534, PR #574)
* ScalaDoc for dev branch automatically published as part of CI (#569):
  * Dev: https://fires.im/firesim/latest/api/
  * Releases will reside at : https://fires.im/firesim/<version>/api
* Add `expect` to `machine-launch` script (#562)
* Add support for Dromajo co-simulation using an extended TracePort (currently only supported by BOOM) (#541,#556)

### Changed
* FIRRTL bumped to version 1.3, Chisel Bumped to version 3.3 (#549)
  * Custom transforms now injected using the FIRRTL Dependency API
* TracerV multiclock changes (PR #441)
  * One TracerV per tile, maximum 7 instructions per tile (resolves #484)
    * One output file per tile
  * FirePerf now supports cores with IPC > 1 (BOOM)
* Assert file no longer copied to manager, baked into driver via header (PR #441)
* Bridges are now diplomatic (LazyModules) (PR #433)
* Synthesized Printfs in different clocks domains are captured in different output files (#441) 
* The default version of Verilator has changed to v4.034 (#550). Since this release adds enhanced support for Verilog timescales, the build detects if Verilator v4.034 or newer is visible in the build environment and sets default timescale flags appropriately.
*  Elaboration output piped to stdout in `buildafi` (PR #433, resolves #440)
* Midas-Level simulation no longer simulates the Shim layer, and instead simulates the module hierarchy rooted at FPGATop #548
* Firesim target project use Chipyard's stage to generate RTL (#557)
* Build setup updates (#544)
  * Users can skip building a toolchain if supplying their own
   * Now requires the user provide `$RISCV` when running under `--library`.
  * Generated env.sh no longer sources chipyard's env.sh when using firesim-as-a-library
* Allow `machine-launch` script to error, log, and use Git 2.2.4 (#538,#563)

### Fixed
* Manager will now report failures in AGFI creation (PR #433, resolves #327)
* Ensure that the NBD kernel module (`nbd.ko`) is built with the non-debug config to avoid symbol compatibility issues (#571).
* Use proper iuscommunity URL during machine launch (#563)
* When using Chipyard-as-Top, properly pass `RISCV/LD_LIB/PATH` variables for `buildafi/infrasetup` (#560)
* plus_arg reader exception thrown when compiling designs with FASED memory widths != 64 bits (PR #577)

### Deprecated

### Removed
* FireSim generatorUtils and subclasses; replaced with Chipyard's stage (#557)


## [1.9.0] - 2020-03-14

### Added
* TracerV + Flame Graph support from FirePerf ASPLOS 2020 paper (PR #496)
  * Docs: https://docs.fires.im/en/latest/Advanced-Usage/Debugging-and-Profiling-on-FPGA/TracerV-with-FlameGraph.html
* Pre-packaged AGFI for Gemmini NN accelerator

### Changed
* Significant overhaul / expansion of Debugging and Profiling on FPGA docs (PR #496)
  * Link: https://docs.fires.im/en/latest/Advanced-Usage/Debugging-and-Profiling-on-FPGA/index.html
* Unification of `FireSimDUT` with `chipyard.Top`, all default firesim configs are now extensions of default Chipyard configs (PR #491)
  * Unification of Configs/Tops between Chipyard and FireSim. Arbitrary Chipyard designs can be imported into FireSim
  * Users can define a FireSim version of a Chipyard config by building a TARGET_CONFIG that specifies WithFireSimDefaultBridges, WithFireSimDefaultMemModel, and WithFireSimConfigTweaks
  * FireSimHarness moved to FireChip. Harness now uses Chipyard's BuildTop key to control which Top to build
  * AGFI naming scheme changed. firesim -> firesim-rocket, fireboom -> firesim-boom
* BridgeBinders system is now generalized as IOBinders for attaching Bridges to the target (PR #491)
* Parallelized verilator Midas-level (ML) simulation compilation (PR #475)
* Default fesvr-step size in ML simulation (PR #474)
  * Passing no plusArg will have the same behavior as on the FPGA
* Disable zero-out-dram by default;  expose in config_runtime.ini (PR #506)
* Update docs to note previously added suffixtag feature (PR #510)

### Fixed
* Fixed synthesizing printfs at the top-level (PR #485)
* Fixed FPGA-level simulation to properly generate GG-side PLL (PR #487)
* Fixed a non-determinism bug due to unreset target-state (PR #499)
* Fixed bug in ML-simulation of verilog black boxes in verilator (PR #499)
* Fixed bug in manager that prevented use of f1.4xlarges with no_net_configs (PR #502)
* Allow simulations without block devices (#519)

### Deprecated
* FireSimNoNIC design option removed, all examples now use FireSim design option
  * Designs can specify inclusion/exclusion of NIC by setting icenet.NICKey, i.e. in TARGET_CONFIG rather than using a different design

### Removed
* Many excess configs in the sample_config inis were removed

## [1.8.0] - 2020-01-25

A more detailed account of everything included is included in the dev to master PR for this release: https://github.com/firesim/firesim/pull/413

### Added
* Black-box Verilog support via external clock-gating PR #388 
  * For the transform to work, the Chisel `Blackbox` that wraps the Verilog IP must have a single clock input that can safely be clock-gated.
  * The compiler that produces the decoupled simulator ("FAME Transform") automatically recognizes such blackboxes inside the target design.
  * The compiler automatically gates the clock to the Verilog IP to ensure that it deterministically advances in lockstep with the rest of the simulator.
  * This allows any Verilog module with a single clock input to be instantiated anywhere in the target design using the standard Chisel `Blackbox` interface.
* Added chisel assertions to check for token irrevocability (non-determinism check) PR #416 
  * Enable by adding `HostDebugFeatures` to your `PLATFORM_CONFIG`
* Support for QCOW2 disk images in the manager. This means that FireSim simulations can now boot directly from qcow2 images---the default linux-uniform image is 40MB as a qcow2 image as opposed to 2GB as a raw .img. Firemarshal support for generating these images is upcoming. This is PR #415 and resolves #411 
* AutoCounter and Trigger features from FirePerf paper (PR #437)

### Changed
* Default buildfarm instances changed from c5.4xlarges to z1d.2xlarges #464  
* Update to Chipyard 1.1.0
* Update FireMarshal to 1.8. This drastically reduces the default root filesystem image sizes and allows for FireMarshal workloads to be in any directory (not just the workloads/ directory).

### Fixed
* Fix managerinit aws configure bug introduced by tutorial modifications. managerinit now correctly runs aws configure again, there is no need to run it separately (docs are updated to reflect this)
* Supernode: Copying back results from supernode simulations now works for all rootfses, not just the zeroeth rootfs of a supernode sim. PR #415 
* Manager: No longer double-copies results for a node that is responsible for triggering a teardown in the networked simulation case. PR #415 

### Deprecated
* N/A

### Removed
* MIDAS submodule removed, now inlined in this repo ([MIDAS]-prefixed commits denote commits that originate from that repo) PR #400 

## [1.7.0] - 2019-10-16

A more detailed account of everything included is included in the dev to master PR for this release: https://github.com/firesim/firesim/pull/313

### Added
* Upgraded MIDAS to Golden Gate (MIDAS II)
* Better support for FireSim as a library. 
  * Toolchains now built through chipyard, with firesim-specific tools added on top 
* Workloads
  * Coremark (PR #288)
  * A linux workload that immediately powers off (PR #321)
* SiFive L2 Cache added to pre-generated AGFIs
  * 512 KiB, single-bank
* Added SHA3 AFIs (PR #368)

### Changed
* FireChip replaced with [chipyard](https://github.com/ucb-bar/chipyard)
  * Derived from project-template, like FireChip
  * Improved integration with other UCB-BAR projects
* MIDAS Endpoints replaced with Golden Gate target-to-host Bridges
  * See Golden Gate docs hosted at docs.fires.im
  * See porting guide for more information
* Submodules moved
  * sim/{firrtl, barstools} -> moved to chipyard
* Config files reorganized (found in `firesim-lib/src/main/scala/configs`)
  * Bridges (formerly endpoints) configured in target generator
  * [PLATFORM\_CONFIG] SimConfigs.scala -> CompilerConfigs.scala
  * [PLATFORM\_CONFIG] util/Configs.scala -> F1PlatformConfigs.scala:
  * [TARGET\_CONFIG] FASEDConfigs moved from SimConfigs.scala to FASEDTargetConfigs.scala (passed to target generator)
* Add a DefaultFireSimHarness
  * Instantiates Bridges usings partial functions matching on Module type
  * Supernoded-ness can be applied to any design using this harness see (`Field` NumNodes)
* FASED memory timing models use maximum # of available sets and ways
  * 2MiB default
* build recipe & hwdb entry names changed to match cache hierarchy
* Boom based targets now use LargeBoomConfig
* Boom based targets now use FireSim DESIGN (PR #368)
* FASED memory models can now have different address widths for each channel
* 0-initialize registers and memories in MIDAS-level VCS simulation
* Default to c5.4xlarges for builds/manager instances
* Make 4.x is needed to build the updated toolchain (PR #345)
  Upgrade procedure for existing EC2 instances:
  ```
  sudo yum install -y centos-release-scl
  sudo yum install -y devtoolset-8-make
  ```

### Fixed
* Block Device widget
  * A bug that would cause the simulation to hang under current reads and writes (PR #308) 
  * A determinism hole that would cause reads to be released prematurely in target-time (PR #325)

### Deprecated
* None

### Removed
* "Developing New Devices" section in documentation
* Clock-domain division in endpoint channels.
  * This to be replaced in 1.8.0 (Early November)

## [1.6.0] - 2019-06-23

A more detailed account of everything included is included in the dev to master PR for this release: https://github.com/firesim/firesim/pull/262
The primary change in this release is a Rocket-Chip bump in FireChip and associated submodules (Chisel, FIRRTL, RISC-V tools)

The release also updates firesim-software, see the changelog at sw/firesim-software/CHANGELOG.md for details (PR #303).

### Added
* PR #250. Add support for flow control via Ethernet pause frames.
* PR #280. Add support for a FIRESIM_RUNFARM_PREFIX env var to differentiate between runfarms for multiple clones of firesim.
    * Resolves #263
* PR #286 Add support for arbitrary host frequency selection (by synthesizing a new PLL).
    * Resolves #252
* PR #287 Add support for runtime-configurable MSHR in FASED
* PR #290 Add endpoint::finish() to let endpoints run code before simulation teardown
* firesim-software PR firesim/firesim-software#31 allows firesim-software to work outside of a firesim environment without the need for sudo (just riscv-tools, qemu, and a few package requirements).

### Changed
* PR #261 Print out that post run hook is running, so it doesn't look like simulation termination is stuck.
* PR #290 Bumps RocketChip from 50bb13d (Sept 25th, '18) to b8baef6 (May 10th, '19)  
  * Bumps Buildroot in firesim-software; points at upstream
  * Bumps RISC-V tools; RISC-V toolchain is built directly from build-setup.sh
    * Resolves #217
  * Enables TracerV support by default
* firesim-software PR firesim/firesim-software#25 updates buildroot to a more recent version in order to support a more recent version of riscv-tools to match the updated tools in this firesim release.

### Fixed
* PR #275. Fix small root volume size for build instances.
    * Increased to 200GB
    * Resolves #274
    * Resolves #265
* PR #287 Fixes FASED LLC timing model bug.
* PR #301 Fixes a bug in synthesizable printf cycle prefixes

### Deprecated
* None

### Removed
* None

### Security
* None

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
* PR #204. Fix support for heterogeneous rootfs's - each job can have its own rootfs, or no rootfs at all

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
