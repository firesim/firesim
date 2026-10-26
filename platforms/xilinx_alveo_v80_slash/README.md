# AMD Alveo V80 with SLASH

This independent platform uses the SLASH DFX shell. The existing
`xilinx_alveo_v80` platform uses its own AVED flow. These images are mutually
exclusive on a card; select the platform that matches the installed shell.

The default host clock is 40 MHz. Generated widget addresses start above the
kernel control aperture. Every deployment must use the driver and generated
register map belonging to its exact qualified image.

## Build and qualify

Prepare the pinned [SLASH dependency](dependencies/README.md) and provide its
static shell and Vivado installation. Generate and deliver the selected design
using FireSim's `PLATFORM=xilinx_alveo_v80_slash` Make flow, then run:

```sh
platforms/xilinx_alveo_v80_slash/build-bitstream.sh \
  --build-dir /path/to/delivered/design --frequency 40 \
  --timeout-seconds 43200 --log-dir /path/to/new/build-logs
python3 platforms/xilinx_alveo_v80_slash/cl_firesim/scripts/qualify-build.py \
  --build-dir /path/to/delivered/design --frequency 40 \
  --shell /path/to/full/static-shell.dcp \
  --static-baseline /path/to/accepted/full-context.dcp \
  --vivado /path/to/Vivado/bin/vivado --timeout-seconds 28800 \
  --output /path/to/new/qualified
```

Set `SLASH_DIR` and `XILINX_VIVADO` for the build. Qualification checks actual
routed timing, clock waveforms and gate-enable half-cycle paths, standard DFX
DRCs, full `pr_verify`, and static programming properties at warning sites.
It does not program hardware or claim native-driver acceptance.

## Candidate and board acceptance

```sh
python3 platforms/xilinx_alveo_v80_slash/export-deployment.py candidate \
  --qualified /path/to/qualified --driver /path/to/native-driver \
  --header /path/to/native-build/FireSim-generated.const.h \
  --metadata 'firesim-deployquintuplet:xilinx_alveo_v80_slash-midasexamples-GCD-NoConfig-DefaultV80SlashConfig,firesim-commit:<recorded-source-commit>' \
  --output /path/to/new/candidate
```

The candidate contains the exact image, driver, register map, reports and
runtime scripts. `run-qualified.py` normally rejects a candidate. Explicit
`--acceptance-test --expect '<required functional output regex>'` permits a
bounded test; use an appropriate design-specific workload and cycle budget.
Replace the example metadata with the build's actual deploy quintuplet and
recorded source commit. Manager reads comma-separated `key:value` fields;
free-form prose is not deployment metadata and is rejected before export.

```sh
python3 /path/to/candidate/run-qualified.py \
  --bundle /path/to/candidate --host-config /path/to/host.json \
  --bdf 0000:ab:00 --acceptance-test --expect '<required output>' \
  --timeout-seconds 1800 --log-dir /path/to/new/board-run -- <driver arguments>
python3 platforms/xilinx_alveo_v80_slash/export-deployment.py accept \
  --bundle /path/to/candidate \
  --board-evidence /path/to/board-run/board-acceptance.json \
  --output /path/to/new/accepted
```

The accepted `firesim.tar.gz` has a relative `xilinx_alveo_v80_slash/` directory
for Manager. Promotion rechecks the candidate and every acceptance log. It
requires programming, clock readback and measurement, native functional output,
and process/health checks before and after the run. Acceptance applies to that
exact driver, image, workload and loaded shared libraries. It does not qualify
other designs or optional debug capture.

Host configuration is private and is not included in the deployment. It names
`bdf`, `board_lock`, `vivado`, `v80_smi`, `clk_force`, `clk_monitor`, `clock_probe`,
and optionally `jtag_hw_server`, `library_path` and a `sudo` argument list. All
paths refer to reviewed host tools. The clock probe must use the generated
ClockBridge registers, measure the clock, and return a nonzero exit on mismatch.
Use a matching SLASH library prefix at the front of `library_path`; accepted
runs require the same resolved library fingerprints. Record the active daemon
and kernel-module fingerprints as part of the host's hardware evidence.

The three clock helpers are host prerequisites, supplied as executable paths:

- `clk_force <bdf> <vbin> <frequency_hz>` sets the clock for the loaded image.
- `clk_monitor <bdf> <vbin>` prints the clock frequency in Hz as an integer
  on its final stdout line; the runner compares that line exactly.
- `clock_probe <vbin> <hCycle_0> <hCycle_1> <hCycle_latch> <frequency_hz>`
  measures the clock through the generated ClockBridge registers and fails
  if the result does not meet its reviewed measurement tolerance.

Each helper exits zero on success and nonzero on failure. The runner supplies
the register offsets and requested frequency. The probe interface leaves card
selection to the host-specific executable or wrapper, so configure its BDF to
match the host configuration. These helpers operate on the image programmed by
the runner; their installation and card selection are part of host preparation.

The runner owns a privileged controller, one board lock, a temporary loopback
JTAG server, and a PTY when stdin is not a terminal. It refuses existing servers
or active native FireSim drivers. The whole operation, lock acquisition and
child commands have explicit deadlines; raw stdout/stderr and a final result
are preserved. Failure does not trigger an automatic reset or reprogramming.
Linux PCIe link speed and width must be valid and agree across all three
functions before programming, after programming, before the native driver,
and after completion. Enumeration and a running daemon alone can report OK
after communication has failed. Unknown speed, width 63, missing functions,
and disabled functions prevent further operations. Failure preserves a final
sysfs-only snapshot without issuing more BAR reads. These checks detect link
loss; they do not identify its root cause or qualify an untested image.
`--verify-only` checks a relocated accepted bundle without accessing the card
or reading host configuration.

## CPU checks

```sh
python3 platforms/xilinx_alveo_v80_slash/tests/test_build_tools.py
python3 platforms/xilinx_alveo_v80_slash/tests/test_deployment.py
```

These tests use explicitly predicted tool/board responses. Real CAD and hardware
acceptance require the separate procedure above.

## Manager integration

The packaged control memory map always spans the resident shell's fixed
128 MiB NoC aperture. The RTL widget register bus uses only its declared low
address bits. The linker precheck rejects a narrower packaged range before
running CAD; full DFX verification still checks the actual implemented NoC.

The qualified runner audits all three SLRs before programming, after programming
and after the native test. It reads DONE/configuration/readback status and PMC
log buffers without resetting or clearing errors. The static shell's SSIT
routing bits (SLR1 bit 1, SLR2 bit 2) are the only allowed error-status bits.
Master/slave SSIT state must agree, the latest PDI load must be complete, and
every SLR buffer must change during programming. Clock operations and the
native driver start only after this check passes. The raw buffers and status
reports are bound into board acceptance.

Use `XilinxAlveoV80SlashBitBuilder` with the matching bit-builder recipe. Its
paths identify reviewed build-host SLASH/Vivado/static inputs and the
manager-host `vrt_home`. Each build uses a new snapshot directory, excludes
old CAD outputs, preserves results, and releases its build host on success or
failure. It produces an offline-qualified candidate; create a hardware database
entry only after exporting the board-accepted `firesim.tar.gz` above.

For a run host, select `XilinxAlveoV80SlashInstanceDeployManager`; see
`deploy/run-farm-recipes/xilinx_alveo_v80_slash.yaml`. Its private FPGA database
is a JSON array with an explicit card and host configuration:

```json
[{"bdf": "0000:ab:00", "host_config": "/etc/firesim/v80-runtime.json"}]
```

Manager checks the extracted accepted bundle, requires its generated register
map to equal the manager's map, and launches its exact accepted native binary.
It preserves a per-run proof directory. Stop requests authenticate the recorded
controller's PID, Linux start time and command, then signal its pidfd and wait
for cleanup. They do not use broad process-name matching or kill an unrelated
hardware server. A normal stop during JTAG programming is honored after that
bounded programming transaction finishes. Hardware coverage is currently scoped
to one card per run host; multi-node target simulations may share that one card.

For local `file://` archive locations, the existing Manager downloader treats
percent escapes literally. Use the literal absolute path rather than URL-encoding
spaces or percent characters. Keep the Manager hardware-database download cache
path free of whitespace: its existing generic archive extractor uses an unquoted
command. Platform-specific launch/copy paths are separately shell-quoted.

Run the additional Manager CPU suite with the FireSim Manager Python environment:

```sh
python3 platforms/xilinx_alveo_v80_slash/tests/test_manager.py
```


## Recorded physical validation

Matching GCD and single-Rocket images passed at 40 MHz. The Rocket runs cover
three ISA programs and Linux boot, root-shell private-disk read/write, and HTIF
poweroff. Printf output streams pass on all three transports, and AutoCounter
CSV values match the CPU reference. Actual Manager execution and real AutoILA
PCIe/HSDP captures also passed; the capture host extension is recorded separately.
Source and artifact identities, retained failures, and acceptance
limits are recorded in [VALIDATION.md](VALIDATION.md).

## Stream transport options

`DefaultV80SlashConfig` uses the CPU-managed stream engine through the DDR
register transport. `DefaultV80SlashFPGAManagedConfig` instead selects the
optional bidirectional `DDRManagedStreamEngine`. Its rings share the target
DDR crossbar, use design-relative addresses, and are allocated separately from
target memory by the runtime. These configurations require different generated
headers, drivers and images; acceptance of one does not qualify the other.

The optional engine adapts complete 512-bit tokens to the negotiated memory
width, bounds bursts by the downstream limit, 256 AXI beats and 4 KiB pages,
and sends short outgoing tails. Incoming reads reserve local response storage
before AR so a blocked bridge cannot prevent an already-issued burst from
returning. `DDRManagedStreamReadBufferDepth` sets the capacity in complete
tokens (128 by default). Ring bases must be 4 KiB aligned. Input rings publish
noncoherent spans before the credit doorbell; counter arithmetic wraps modulo
2^32, and waits fail with a bounded timeout. AXI response errors and malformed
read `last` signals are asserted in RTL simulations.

CPU regression includes the actual ring drivers, generated register map,
full Golden Gate platform and a predicted external DDR model. It checks both
64/512-bit ports, short tails, 4 KiB boundaries, ring wrap, stalled address/data
channels, reserved read storage, simultaneous directions and response errors.
These checks do not establish FPGA timing or physical-card correctness.


`DefaultV80SlashMMIOConfig` selects the optional low-bandwidth MMIO path. A
separate MMIOStreamWidget forwards 32-bit words to the unchanged CPU-managed
stream engine. Posted read commands and a bounded response FIFO preserve
control response stability under backpressure; only explicit data reads
consume queued words. This path does not need external DDR staging. It still
requires its own matching generated header, driver, image and board acceptance.

Pure MMIO designs may have zero DDR channels. Such designs do not accept a
nonzero `+dram_alloc` reservation; omit that argument. The runtime rejects
nonzero DDR DMA/ring requests when the generated design has no DDR aperture.
