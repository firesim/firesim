# Bug Report: Vivado hw_server wedge on back-to-back per-board JTAG programming

## Summary

Back-to-back invocations of `program_fpga.tcl` (each in a separate Vivado
process) cause the Vivado `hw_server` daemon to enter an unrecoverable wedged
state. The third Vivado invocation hangs indefinitely at `connect_hw_server`,
burning ~12% CPU, and never completes. This blocks `firesim infrasetup` on
multi-board hosts.

## Environment

- **Host**: harp (Intel Xeon Gold 6126, Ubuntu 24.04)
- **Vivado**: 2025.1 (SW Build 6140274)
- **FPGAs**: 8x Corigine XB-10 (Xilinx VU19P xcvu19p-fsvb3824-2-e)
- **JTAG adapters**: 8x Digilent JTAG-HS2 (FTDI FT4232H, USB via onboard hub)
- **FireSim branch**: `xb10_port`

## Root cause

`program_fpga.tcl` calls `close_hw_target` and `exit` but never calls
`disconnect_hw_server` or `close_hw_manager`. Vivado's `connect_hw_server`
spawns a persistent `hw_server -d` daemon on TCP port 3121 that is **not**
killed when the Vivado process exits.

When the next Vivado process starts and calls `connect_hw_server`, it connects
to the **stale** hw_server instead of spawning a fresh one. The stale hw_server
holds open USB file descriptors and accumulated JTAG state from the previous
session. After two rounds of this, the stale hw_server enters a degenerate
protocol state and the third Vivado client hangs forever at
`connect_hw_server -allow_non_jtag`.

## Reproduction

Test script: `test_hw_server_wedge.sh`

The original infrasetup flow calls `firesim-fpga-util.py --bitstream --bdf`
per board, which internally does:
1. PCI disconnect (clear SERR, clear fatal error, remove device)
2. `vivado -mode batch -source program_fpga.tcl` (programs one board)
3. PCI reconnect (rescan, enable memory-mapped transfers)

The test replicates steps 1-3 for each board sequentially with zero inter-board
delay, using the **original** `program_fpga.tcl` without `disconnect_hw_server`.

### Results (8-board run, 2026-04-30)

```
Board 1/8: 1a:00.0  OK  90s   hw_server spawned: PID 128987
Board 2/8: 1b:00.0  OK  80s   stale hw_server reused, replaced: PID 129877
Board 3/8: 60:00.0  HUNG      connect_hw_server blocked for 4h 25m until killed
Boards 4-8:         not reached
```

### Detailed wedge state at 30 minutes

**Processes:**
```
PID 129877  hw_server  11.7% CPU  30m53s uptime (stale from board 2)
PID 130360  vivado      1.2% CPU  29m44s uptime (hung board-3 client)
```

**TCP connection (loopback:3121):**
```
hw_server → vivado:  195,828 bytes sent (2,119 data segments)
                     42 bytes pending in send buffer
                     last sent: 1s ago (periodic heartbeat)

vivado → hw_server:  73 bytes sent total (initial connect request only)
                     last sent: 29m 31s ago (never responded after connect)
```

hw_server sends exactly **51 bytes every ~7.5 seconds** — a JTAG scan heartbeat.
Vivado receives these but never sends any reply after the initial 73-byte connect
request. Classic protocol-level deadlock: hw_server is waiting for a client
response that will never come; Vivado is waiting for enumeration to complete.

**hw_server held 8 USB JTAG handles from previous session:**
```
fd 25 → /dev/bus/usb/001/010  Digilent/210308B356F7
fd 28 → /dev/bus/usb/001/012  Digilent/210308B356F1
fd 31 → /dev/bus/usb/001/006  Digilent/210308B3570A
fd 34 → /dev/bus/usb/001/009  Digilent/210308B356FF
fd 37 → /dev/bus/usb/001/008  Digilent/210308B35714
fd 40 → /dev/bus/usb/001/011  Digilent/210308B33FC9
fd 43 → /dev/bus/usb/001/005  Digilent/210308B35708
fd 46 → /dev/bus/usb/001/007  Digilent/210308B3570B
```

**Thread states (unchanged for entire 4h 25m hang):**
```
hw_server: 13 threads — 7 in inet_csk_accept, 3 in futex_wait, 1 hrtimer, 1 pause, 1 skb_wait
vivado:     7 threads — 4 in futex_wait, 1 hrtimer, 1 wait_woken, 1 skb_wait
```

At 4h 25m, hw_server had sent 195,828 bytes across 2,119 segments. Vivado still
73 bytes sent total. Both processes were killed manually.

## Fix

Add proper hw_server cleanup to `program_fpga.tcl`:

```diff
 program_hw_devices [get_hw_device]
 refresh_hw_device [get_hw_device]
 close_hw_target
+disconnect_hw_server
+close_hw_manager

 exit
```

This ensures each Vivado session tears down its hw_server daemon before exiting,
so the next invocation spawns a fresh one.

### Verification

With the fix applied, all 8 boards programmed successfully via the per-board
flow with zero inter-board delay:

```
Board 1/8: 1a:00.0  OK 63s  (fresh hw_server each time)
Board 2/8: 1b:00.0  OK 63s
Board 3/8: 60:00.0  OK 63s
Board 4/8: 61:00.0  OK 62s
Board 5/8: b1:00.0  OK 61s
Board 6/8: b2:00.0  OK 62s
Board 7/8: da:00.0  OK 62s
Board 8/8: db:00.0  OK 61s
Total: 497s
```

## Additional improvement: single-session fleet programming

Beyond the hw_server fix, we implemented `program_fpga_fleet.tcl` which programs
all boards in a single Vivado session. This avoids the hw_server issue entirely
(one connect/disconnect pair) and reduces Vivado startup overhead.

### Timing comparison (8 boards, same bitstream)

| Approach | Total time | Per-board | Vivado overhead |
|---|---|---|---|
| Per-board (8 Vivado launches) | 497s | 62s (40s JTAG + 22s startup) | ~176s total |
| Fleet (1 Vivado session) | 393s | 40s JTAG + 53s PCI ops | ~20s total |
| **Speedup** | **1.26x** | saved 104s | |

The fleet approach is both faster and more robust: it eliminates the possibility
of hw_server state accumulation across invocations.

## Files

- `test_hw_server_wedge.sh` — Reproduction script (uses original TCL without cleanup)
- `test_fleet_vs_perboard.sh` — Timing comparison script
- `hw_server_wedge_test/` — Wedge test logs and original `program_fpga_original.tcl`
- `fleet_vs_perboard_test/` — Timing comparison logs
- `platforms/xilinx_alveo_u250/scripts/program_fpga_fleet.tcl` — Fleet programming TCL
- `platforms/xilinx_alveo_u250/scripts/program_fpga.tcl` — Fixed per-board TCL
