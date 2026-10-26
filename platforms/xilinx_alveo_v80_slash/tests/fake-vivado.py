#!/usr/bin/env python3
"""Predicted reports for orchestration tests; never a physical signoff result."""
import os
from pathlib import Path
import sys
import time

args = sys.argv[1:]
script = Path(args[args.index('-source') + 1]).name
parameters = args[args.index('-tclargs') + 1:]
mode = os.environ.get('SLASH_TEST_REPORT_MODE', 'pass')
if mode == 'timeout':
    time.sleep(60)
if mode == 'tool-error':
    raise SystemExit(23)
if script == 'extract-optimized-rm.tcl':
    Path(parameters[1]).write_text('predicted RM')
elif script == 'assemble-full-context.tcl':
    out = Path(parameters[2]); out.mkdir()
    (out / 'full-context.dcp').write_text('predicted context')
elif script == 'qualify-routed.tcl':
    out = Path(parameters[1]); out.mkdir()
    (out / 'clock-qualified.dcp').write_text('predicted qualified context')
    (out / 'partial.pdi').write_bytes(b'predicted image')
    (out / 'timing-summary.txt').write_text('WNS(ns) TNS(ns) WHS(ns) TPWS(ns)\n---\n'
        + ('-0.1' if mode == 'timing-fail' else '0.1') + ' 0 0 1 0.02 0 0 1 0.2 0 0 1\n')
    (out / 'gated-clock-waveform-check.txt').write_text('TIMING-5#' if mode == 'waveform-fail' else 'clean')
    (out / 'clocks.txt').write_text('host_clock ' + ('20.000' if mode == 'clock-fail' else '25.000') + ' {0 12.5}\n')
    for name in ('standard-dfx-drc.txt', 'default-drc.txt', 'abstract-dfx-drc.txt'):
        (out / name).write_text('Error' if mode == 'drc-fail' else 'no violations')
elif script == 'check-negedge-gates.tcl':
    (Path(parameters[1]) / 'gate-enable-signoff.txt').write_text('predicted falling-edge enable paths')
elif script == 'check-dfx-flow.tcl':
    out = Path(parameters[1]); out.mkdir()
    (out / 'pr-verify.txt').write_text('incompatible' if mode == 'pr-fail' else
        'All DCPS provided to pr_verify have locked static\nare compatible\nStatic site SLICE_X1Y1\n')
    for name in ('standard-dfx-drc.txt', 'default-drc.txt', 'abstract-dfx-drc.txt'):
        (out / name).write_text('no violations')
elif script == 'inspect-static-config.tcl':
    first, second, sites, output = parameters
    Path(output).write_text(f'{first}|SLICE_X1Y1|static/lut|INIT|0\n'
                           f'{second}|SLICE_X1Y1|static/lut|INIT|0\n')
else:
    raise RuntimeError(f'Unexpected mocked script: {script}')
print(f'Predicted orchestration stage {script}: {mode}')
