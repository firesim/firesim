# SLASH V80 validation record

Recorded on 2026-10-26. Native source for GCD, Rocket, DDR/FPGA-managed
Printf, AutoCounter, AutoILA and Manager workload is FireSim commit
`90d5e2c388ef02df114f7da434aa2c059b689815`. The zero-DDR MMIO Printf
driver uses the subsequent runtime-only repair
`93c273f04e25e0b9a6917b191088ddfaed08333d`; its FPGA RTL and register map
are unchanged. Later documentation edits do not change those tested binaries. These results cover the specific designs,
artifacts and host software recorded below.

## Physical results

| Design or path | Result |
| --- | --- |
| GCD, 40 MHz | GCD(64,48)=16; native PASS after 12 target cycles |
| Rocket `rv64ui-p-add` | PASS after 4,738 cycles; matches CPU RTL simulation |
| Rocket `rv64um-p-mul` | PASS after 4,738 cycles; matches CPU RTL simulation |
| Rocket `rv64mi-p-csr` | PASS after 3,202 cycles; matches CPU RTL simulation |
| Rocket Linux | Kernel 6.6 boot, root login, `riscv64`, synced private-disk write/read, guest HTIF poweroff; native PASS after 953,909,890 cycles |
| DDR CPU-managed Printf stream | All 64,004 output lines match the CPU reference; native PASS after 16,003 target cycles |
| MMIO CPU-managed Printf stream, no DDR channel | All 64,004 output lines match the CPU reference; native PASS after 16,003 target cycles |
| FPGA-managed DDR Printf stream | All 64,004 output lines match the CPU reference; native PASS after 16,003 target cycles |
| AutoCounter | All seven columns, three samples, CSV labels and clock metadata match the CPU target-RTL reference |
| AutoILA GCD | Native PASS after 12 cycles; real PCIe/HSDP captures: 1,024 samples initially (64,48), 1,024 final samples all (16,16) |
| Manager | Unchanged task registry and real Fabric/SSH launch, completion detection and output collection for the accepted Rocket ISA add workload; native PASS after 4,738 cycles |
| SDK host DDR DMA | Four 4 KiB pages across two adjacent 512 MiB allocations; both transfer directions compare equal |
| Current native runtime span splitting | One 128-byte transfer crossing the 512 MiB boundary, split into 32+96 bytes; both physical DMA readbacks compare equal |

Each GCD/Rocket/Printf/AutoCounter/AutoILA/Manager workload passed the qualified runner's 17 checks, including
pre/post PCIe, card/service and three-SLR PLM/device audits, clock readback and
measurement, native exit and functional output. The tested links were
32 GT/s x8. Full-context timing at 40 MHz had zero TNS/THS, with WNS/WHS
0.085/0.018 ns for GCD and 0.085/0.012 ns for Rocket. Static-shell compatibility,
gate-enable timing, DFX checks and static-configuration comparison passed before
programming each candidate.

The Rocket target was `FireSimRocketMMIOOnly1GiBDRAMConfig`, using
`DefaultV80SlashConfig` and a 1 GiB guest memory. This target has UART,
block-device and TSI bridges, with no NIC. Its Chipyard source checkout was
`dbc082e2206f787c3aba12b9b171e1704e15b707`; the FireSim gitlink was explicitly
overridden to the native-source commit above. The target configuration extends
`FireSimRocketMMIOOnlyConfig` with
`new freechips.rocketchip.subsystem.WithExtMemSize((1 << 30) * 1L)`.

## Retained failures and Linux acceptance

The first Linux trial stopped normally at its configured 300,000,001 target
cycles, after reaching the kernel but before userspace. This was an insufficient
test budget. Its logs are retained. A subsequent read-only card/service,
link/driver-binding and AER check passed, without reset or programming.

The revised trial used the same image, native binary and input hashes, a new
private disk, a 10-billion-target-cycle cap and the same 1,800-second deadline.
It completed in 86.1 seconds of native execution, below both limits. The outer
UART-text classifier initially rejected the run because the final shutdown
message was not fully drained before HTIF completion. That rejection is retained.
Independent inspection of the compiled termination path and raw acceptance logs
confirms guest FESVR/HTIF exit 0 following `sync; poweroff -f`; heartbeat failure
and cycle-budget exhaustion are excluded. All 17 physical checks passed. This
establishes guest forced poweroff, not a complete service shutdown sequence.
No additional board run was used to change that classification.

The first pure-MMIO Printf trial exited before opening the SDK device because
the native constructor required one nonempty DDR address space. A read-only
follow-up confirmed healthy services, link/driver bindings and zero AER counters.
The repair permits zero channels with a zero DDR aperture and rejects invalid
memory maps, DDR-stream metadata without DDR, and out-of-range reservations
before opening the SDK. It passed 68 CPU regression cases and three real-SDK
header compilation variants; identical new tests reproduce two failures and
four passing controls against the parent. Only the native executable was rebuilt.
The successful trial omitted `+dram_alloc` on this zero-DDR design and reused
the exact previously signed-off FPGA image. The first failure is retained.

## Manager and debug acceptance details

The actual Manager task registry ran without mocked operations, using a private
loopback SSH daemon and the prebuilt accepted Rocket bundle. The host's HTTP proxy
caused the existing AWS metadata probe to misidentify the host; bypassing the proxy
for `169.254.169.254` corrected detection without modifying Manager source. The
preceding stopped launch waited at an outer sudo prompt and did not access hardware.

The successful workload's native stdout/stderr are retained in the qualified
proof directory. Manager's collected `uartlog` contains successful qualification
JSON referring to that directory, rather than streamed native console text.
An outer assertion incorrectly expected the native PASS line in `uartlog`; the
original rejection remains, and the corrected acceptance checks the actual native
logs and matching collected JSON without rerunning hardware. `HW_CFG_SUMMARY` was
created locally by Manager; a redundant remote-output entry produced rsync code 23
because there was no remote file of that name. This result does not claim
warning-free collection or interactive native-console forwarding.

The first AutoILA entry programmed and verified the PDI but stopped before native
or debug I/O when the audit interpreted SLR2's circular log as incomplete. The
16 KiB log had wrapped, splitting its newest Done line across the physical end and
start. The repaired decoder uses the captured RTCA StartAddr, Len, Offset and full
flag, validates their layout/bounds and restores chronological order. It passed
CPU negative/positive tests, replay of that original snapshot and 15 earlier
physical audits. A continuation required unchanged complete debug-ring contents,
RTCA ring metadata, SSIT state and status registers. It reused that programmed
image, then passed the remaining clock/native/debug/post-run checks. Live non-log
PMC firmware bytes are not used as image identity. Neither decoder correction
changed FPGA or native code.

AutoILA capture used a separately recorded private HSDP host-tool extension under
the same qualified board lock. The public launcher has no automatic capture option;
this establishes the generated hardware/debug path and actual captured values,
not a complete distributed capture workflow. The host adapter library SHA256 was
`e09f321b60325aaf3bc283abb71911cde0c833aa64e8a8332379d200ead879be`.

## Artifact identities

| Artifact | SHA256 |
| --- | --- |
| GCD native binary | `71e207f5cf9f51cdd04718a2b2426fd876b8020de10260133ab86253557f251b` |
| GCD VBIN | `5f762897137bc90709e0a2c6234bb5233cd7226eb25d10ff93ebfb8cfe5f5ef5` |
| GCD generated header | `6fe75650f1bfbd2e943701161ee10668ab9d1e7ed5e7fc621cad5f2f475752cf` |
| Rocket native binary | `4b6560b4c7bcbbe1483074b5d7ad3cac50e3e600f88d8548bc2e369d42894e38` |
| Rocket VBIN | `74a8636f187f4136f563ba25e662dae99d737de0a61d48b0d6b6228c8180c468` |
| Rocket generated header | `cbc4db2671427d424e85bdabca996b3d2809e3add9bec5b655c938f81d00d12e` |
| Linux payload | `ed41276517fdf463b8407091c4f80dafd0c4ec9ec8e948935d550b11b35ee4c5` |
| Original Linux disk, unchanged | `6723fd43073a96d7bb24542c7962b0e4798c6dfaf3d465634e508a6879396408` |

| Artifact | SHA256 |
| --- | --- |
| DDR Printf native | `1427e28c6acc5a9b746381452452270d0ece2c234fb68dab90149d971e6ede3a` |
| DDR Printf VBIN | `83a977bee9eaa4bf7bdd5a8fe1d3104cf77f86e04047c9a70bcdde4650f9bc4e` |
| MMIO Printf repaired native | `c775172a13e7232652fbc47934e878169e5f576dd49dea23c15228b7e9fa5507` |
| MMIO Printf VBIN | `83089c8525581e3a0df0e3b6a2b1ae19fd134cf261d94df4b8123b0e8ecbe30f` |
| FPGA-managed Printf native | `a479c2204713dc3c2a18b26bf595da55f6278be9208aeeccbbb6bb464206f960` |
| FPGA-managed Printf VBIN | `0234c537ff85ee07a6aa88fd5071ca3708f3b3018618c70142b3b083941bab0d` |
| AutoCounter native | `af0e689653b3518049fa743b3c7e1f140c262ff8c00a616f18e7cbcb6b211561` |
| AutoCounter VBIN | `346d20a447b1527f988755974dcbc4f7b880071146b9eda3f0c315f72e8cbad4` |

The GCD CAD source predates native source; its generated RTL, header, constraints
and relevant build inputs were compared for equivalence. Rocket CAD and native
use the same source and CPU-verified generated inputs. Host kernel/module,
daemon and runtime-library fingerprints are retained with the physical proofs.
The deployed host stack was held fixed through these trials.

## Acceptance boundaries

The Printf trials exercise target-to-host stream output. They do not qualify
host-to-target stream input, guest networking, other
memory models, multi-card operation or the separate static-v3 timeout-isolation
prototype. Each needs its own applicable evidence. A process deadline does not
prove that a physical MMIO fault can be interrupted. The earlier PCIe failure's
root cause remains unresolved.

Source and selected diagnostics may be shared separately; generated vendor
images/checkpoints, private host configuration and raw host-system logs are not
part of this source contribution.

Selected text-only evidence is published separately on the contributor fork's
[`v80-slash-validation-20261026` branch](https://github.com/wxhenry/firesim/tree/v80-slash-validation-20261026/validation).
The MIDAS package includes original-code failures and passing controls; board logs
state their matching-source and workload limits. Vendor images/checkpoints and
private host configuration are excluded.
