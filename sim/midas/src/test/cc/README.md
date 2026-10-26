# MIDAS C++ driver regressions

These deterministic tests compile and link the production driver sources with a
register-level `simif_t` implementation. They require Python 3, a C++20 compiler
with AddressSanitizer, and GMP development headers/libraries. No generated target,
Scala build, FPGA, board-management tools, or device files are used.

From the FireSim root:

```sh
python3 sim/midas/src/test/cc/run_driver_tests.py --output /tmp/midas-drivers-fixed
```

The output directory must not exist. Select an individual suite with
`--suite fased`, `--suite simulation`, or `--suite scheduler`. To compare the same
tests against another checkout, including the unmodified upstream baseline:

```sh
python3 sim/midas/src/test/cc/run_driver_tests.py \
  --source-root /path/to/baseline --output /tmp/midas-drivers-baseline
```

The runner preserves the compiler identity, source commit/status and SHA256
fingerprints, full compilation command, build stdout/stderr, per-case stdout/stderr,
exit codes, and elapsed time. A failing regression or build returns nonzero;
timeouts return 124 in the result. Builds have a 180-second limit and each case a
20-second limit. There is no random seed or simulated RTL cycle budget: the tests
exercise finite C++ operations against deterministic registers.

| Suite | Coverage |
| --- | --- |
| `fased` (12 cases) | Histogram/range address-map lifetime; exact model indices; independent model output files; hardware-default option isolation; full uint32 decimal/hexadecimal values; invalid numeric input |
| `simulation` (7 cases) | Failures reported during bridge finalization; earlier run failures preserved; deterministic first finalization failure; successful and nonterminating bridges; timeout behavior |
| `scheduler` (2 cases) | Actual FASED top-level profiling callback after its constructor returns; repeated profiling intervals; profiling-disabled control |

The scheduler test includes the production `fasedtests_top.cc` because that driver
defines its class in its implementation file. It executes the actual callback and
scheduler, with a real FASED driver recording register reads. The simulation tests
run the real initialization, fingerprint, clock and finalization flow, using a test
bridge that reports its error only after `finish()`.

AddressSanitizer stack-use-after-return detection is enabled. Leak checking is
disabled because `AddrRangeCounter` has an existing raw allocation without a
destructor; these regressions do not claim leak-free operation. They also do not
establish generated-RTL correctness, FPGA compatibility or hardware acceptance.

## Initialization, entry and widget-control regressions

The simulation suite also rejects invalid INIT_DONE values and bounds returned
pending responses with a paced initialization deadline. The deadline cannot
interrupt a blocked physical access. `run_entry_tests.py --output <new-directory>`
checks native exception reporting and cleanup.

The Scala suites `midas.MCRFileSpec` and `midas.MCRPaddingSpec` compile the actual
MCRFile into Verilator models. They check stable AXI responses under backpressure,
one source handshake per request, reset while a response is pending, and
completion of padding slots without accessing a real register.
