# radiance-4sm-gputest

Smoke test for the Radiance GPU on FireSim / FireAxe, 4-SM `RadianceHBMConfig` variant of
`radiance-2sm-gputest` (same Muon kernel, same Rocket host program), for the memory/compute split
of `FireSimRadianceHBMConfig` across two XB-10s (all four `RadianceCluster`s on one FPGA, Rocket +
split L2 + 17 DRAM channels on the other).

Differences from the 2-SM test:

* 4 clusters x 2 cores x 16 lanes are checked (`NCLUSTERS 4`, 1024 result slots; mhartid =
  `cluster<<8 | core<<7 | warp<<4 | lane`, the Muon CSR layout in `SFUPipe.scala`).
* `src/scramble_gpu_elf.py` implements the full `AddressHashParams` model (`channelsPerSlice`,
  `chanSetStride`): RadianceHBMConfig hashes GPU memory (2 GiB at 0x1_0000_0000, 32 B units) over
  16 DRAM channels, 2 per GPU L2 slice, with `chanSetStride = 128` (256 sets / 2 channels, from the
  elaborated `*.l2_gpu.json`: 8 banks, 2 x 128 MiB channels each, 8 ways, 32 B blocks).
  The hashed ELF is needed because FireSim's LoadMem path writes DRAM directly, past the hash.

Build: `source env.sh` in chipyard, then `src/build.sh` (needs riscv64-unknown-elf-gcc).
Run: `workload_name: radiance-4sm-gputest.json`, topology `fireaxe_radiance_4sm_memcompute_xb10_config`,
`plusarg_passthrough: "+fesvr-step-size=5000"`, then `firesim infrasetup && firesim runworkload`.
Expected uartlog tail: eight `16/16 lanes wrote their id` lines, `gpu-test: PASS`, `*** PASSED ***`.
