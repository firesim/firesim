# radiance-2sm-gputest

Smoke test for the Radiance GPU on FireSim / FireAxe (first passed 2026-10-28 on two XB-10s,
2-SM split: SM1 on one FPGA, everything else on the other).

* `src/encode_muon.py` hand-assembles an 8-instruction Muon kernel (64-bit Muon encoding, field
  positions from `radiance/muon/Decode.scala`, every encoder checked against words taken from the
  real cyclotron kernels): each lane reads `mhartid`, stores `0xC0DE0000 | mhartid` to
  `results[mhartid]` (Muon 0x1010_0000 = system 0x1_1010_0000) and retires the warp (`vx_tmc x0`).
* `src/gpu_test.c` (Rocket, bare metal, HTIF): clears the slots, releases the GPU soft reset
  (0x4100_0000), waits for all-finished (0x4100_0008), prints per-core finish bits
  (0x4100_0010+4i), then checks the 4 cores x 16 lanes (`mhartid = cluster<<8 | core<<7 |
  warp<<4 | lane`; warp 0 boots with all lanes active) and exits 0 on PASS.
* `src/gpu_test.ld` = chipyard `tests/htif.ld` + a `.gpu` section at 0x1_1000_0000 (Muon
  startAddress 0x1000_0000); `src/gpu_blob.S` incbins the kernel.
* `src/scramble_gpu_elf.py` rewrites GPU LOAD segments into the hashed DRAM layout
  (`AddressHashParams.forward`: 2 GiB at 0x1_0000_0000, 4 slices, 32 B units) because FireSim's
  TSI bridge loads straight into DRAM past the GPU address hash. Use `gpu_test.hashed.riscv`
  with the default loader, or `gpu_test.riscv` with `+tsi-no-loadmem` (plusarg added to
  firechip's tsibridge.cc).

Run: set `workload_name: radiance-2sm-gputest.json` and
`plusarg_passthrough: "+fesvr-step-size=5000"` (HTIF printf costs ~40 M target cycles with the
default 2 M-cycle fesvr step) in config_runtime.yaml, then `firesim infrasetup && firesim runworkload`.
Expected uartlog tail: four `16/16 lanes wrote their id` lines, `gpu-test: PASS`, `*** PASSED ***`.
