# SLASH runtime CPU checks

Run from a FireSim source root:

```sh
python3 sim/midas/src/test/cc/run_slash_runtime_tests.py --output /tmp/new-slash-check
```

The output directory must not exist. The script blocks with a 180-second total
deadline, a 60-second build limit and 12 seconds per modeled case. It saves
commands, source commit/status/SHA256, sanitizer output and results.

The tests compile the actual SLASH runtime against an explicit SDK test double.
The double uses sparse predicted card memory and ordinary host allocations;
it never opens a PCI device, links libvrt or programs hardware. Base simif
plumbing is supplied by the fixture. One executable disables stream metadata;
a second enables synthetic DDR metadata and immediate modeled completion.
It checks staging synchronization and command ordering without modeling an
RTL DMA engine. A separate command-helper executable checks register words,
response errors, validation and the 60-second deadline with an advancing test
clock; the production clock remains `std::chrono::steady_clock`.

The scenarios cover capture ownership/order, overlapping writes, empty
writes, range/overflow rejection, safe control aliasing, modeled 512 MiB
allocation splits and the 1 GiB tail, stream ring placement/synchronization,
exhaustion, rejecting target-window growth after stream placement, injected
allocation failures/incorrect placement with cleanup and retry, staging paths,
and DDR/MMIO command helpers. The allocator double injects null/exception
failures and misplaced regions; it does not reproduce the SDK buddy allocator
or prove fragmentation recovery on a card. These checks do not establish
physical DMA, FPGA timing, generated register-map correctness or board behavior.

To also compile three metadata variants against an installed SDK, supply
`--sdk-prefix /path/to/sdk --dependency-prefix /path/to/dependency-prefix`.
The latter provides JSON, libxml2 and ZMQ headers. These are compile-only
fixtures with synthetic metadata, not generated hardware address maps; SDK
functions are never executed. `CONDA_PREFIX` can provide the dependency prefix.

For comparison with the historical local port, use `--legacy-runtime
--source-root /path/to/historical/firesim`. Official main has no such legacy
backend. The same behavioral scenarios are used, with an adapter for the
changed stream allocation interface.

Four SLASH-only cases also reject all aligned words in the reserved kernel
window `[0, 0x40)` before register I/O or pending DMA flush, accept the last
valid word at `0xffc`, reject invalid optional fence addresses before opening
the SDK device, and preserve valid presence-fence reads around a control write.
Existing ordering cases exercise the first valid word at `0x40`. These guards
validate addresses; they do not contain a stalled physical PCIe transaction.

`--source-root` can also select an earlier extracted SLASH source for regression
comparisons. If its DDR helper predates the injectable clock, the timeout case
is explicitly skipped rather than performing a real 60-second busy wait.

The extracted runtime requires `+v80-use-loaded-image`: a separate qualified
deployment must program and verify the matching FPGA image first. Reserve the
target memory required by the workload using `+dram_alloc` before allocating
stream rings. This CPU checkpoint is preparation for platform/build/Manager
integration and does not qualify a deployment.
