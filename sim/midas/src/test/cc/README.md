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
| `simulation` (24 cases) | Failures reported during bridge finalization; earlier run failures preserved; deterministic first finalization failure; successful and nonterminating bridges; timeout behavior; pending initialization and immediate rejection of invalid/unavailable INIT_DONE reads; bounded and paced initialization polling; strict timeout parsing |
| `scheduler` (2 cases) | Actual FASED top-level profiling callback after its constructor returns; repeated profiling intervals; profiling-disabled control |

The scheduler test includes the production `fasedtests_top.cc` because that driver
defines its class in its implementation file. It executes the actual callback and
scheduler, with a real FASED driver recording register reads. The simulation tests
run the real initialization, fingerprint, clock and finalization flow, using a test
bridge that reports its error only after `finish()`.

Initialization polls immediately, then waits one millisecond between pending
responses, with a default 60-second deadline. `+init-timeout-ms=N` selects an
integer from 1 through 600000; duplicate and malformed options fail. The deadline
and pacing tests use short real waits and verify that initialization failures do
not proceed to fingerprint, clock or bridge accesses. These limits apply only
when register reads return; they cannot interrupt a stalled hardware access.

AddressSanitizer stack-use-after-return detection is enabled. Leak checking is
disabled because `AddrRangeCounter` has an existing raw allocation without a
destructor; these regressions do not claim leak-free operation. They also do not
establish generated-RTL correctness, FPGA compatibility or hardware acceptance.

Native entry-point regressions compile the production `main.cc` with an entry
stub that returns success/failure or throws standard/nonstandard exceptions:

```sh
python3 sim/midas/src/test/cc/run_entry_tests.py --output /tmp/midas-entry-tests
```

The four ASan cases verify exit codes, error messages, local stack unwinding and
global cleanup. They do not instantiate a simulation interface or access devices.
The runner requires a new output directory and defaults to a 60-second total
deadline. Each child is bounded and raw logs/source fingerprints are preserved.
