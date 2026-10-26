#!/usr/bin/env python3
"""Qualify a SLASH FireSim build offline against its full static shell.

The output archive binds the routed image to the generated register map.
A separate deployment acceptance must bind and exercise its native driver.
"""
import argparse
import hashlib
import io
import json
import math
import os
import signal
import time
from pathlib import Path
import re
import subprocess
import tarfile
import xml.etree.ElementTree as ET


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--shell', type=Path, required=True)
    parser.add_argument('--static-baseline', type=Path, required=True)
    parser.add_argument('--frequency', type=int, required=True, help='Runtime MHz')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--timeout-seconds', type=int, default=28800)
    parser.add_argument('--vivado', default='vivado')
    args = parser.parse_args()
    if args.timeout_seconds <= 0:
        raise ValueError('Timeout must be positive')
    if not 1 <= args.frequency <= 300:
        raise ValueError('Frequency must be in [1, 300] MHz')
    build, shell, baseline = (p.resolve(strict=True) for p in
                              (args.build_dir, args.shell, args.static_baseline))
    source_vbin = build / 'firesim.vbin'
    routed = build / 'firesim.vbin.prj/slash_rm/slash_firesim.runs/impl_1/top_wrapper_routed.dcp'
    header = build / 'design/FireSim-generated.const.h'
    rtl = build / 'design/FireSim-generated.sv'
    for artifact in (source_vbin, routed, header, rtl):
        artifact.resolve(strict=True)
    output = (args.output or build / 'qualified').resolve()
    output.mkdir(exist_ok=False)
    scripts = Path(__file__).resolve().parent
    started = time.monotonic()
    deadline = started + args.timeout_seconds
    source_manifest = dict(timeout_seconds=args.timeout_seconds, hardware_access=False,
                           scripts_sha256={p.name: digest(p) for p in sorted(scripts.iterdir()) if p.is_file()},
                           source_artifacts_sha256={str(p): digest(p) for p in (source_vbin, routed, header, rtl, shell, baseline)})
    (output / 'input-manifest.json').write_text(json.dumps(source_manifest, indent=2) + '\n')

    def status(stage, state='running', **extra):
        temporary = output / 'status.json.tmp'
        temporary.write_text(json.dumps(dict(stage=stage, state=state, **extra)) + '\n')
        temporary.replace(output / 'status.json')

    def run(stage, command):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Overall qualification deadline expired')
        status(stage)
        directory = output / (stage + '-work')
        directory.mkdir(exist_ok=False)
        command = list(map(str, command))
        (output / (stage + '-command.json')).write_text(json.dumps(command, indent=2) + '\n')
        with (output / (stage + '.stdout.log')).open('w') as out, (output / (stage + '.stderr.log')).open('w') as err:
            process = subprocess.Popen(command, cwd=directory, stdout=out, stderr=err, start_new_session=True)
            try:
                code = process.wait(timeout=min(7200, remaining))
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    pass
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
                raise TimeoutError(f'Qualification stage {stage} exceeded its bounded deadline')
            if code:
                raise subprocess.CalledProcessError(code, command)

    def vivado(stage, script, *parameters):
        run(stage, [args.vivado, '-mode', 'batch', '-source', scripts / script,
                    '-tclargs', *parameters])

    try:
        rm = output / 'routed-rm.dcp'
        context = output / 'full-context'
        signed = output / 'signoff'
        vivado('extract', 'extract-optimized-rm.tcl', routed, rm)
        vivado('full-context', 'assemble-full-context.tcl', shell, rm, context)
        vivado('timing', 'qualify-routed.tcl', context / 'full-context.dcp', signed,
               args.frequency, 'export')
        vivado('gate-enable', 'check-negedge-gates.tcl', signed / 'clock-qualified.dcp', signed)
        vivado('dfx', 'check-dfx-flow.tcl', signed / 'clock-qualified.dcp', signed / 'dfx-flow', shell)
        # pr_verify warnings about static site programming require explicit
        # property comparison against the accepted static configuration.
        pr = (signed / 'dfx-flow/pr-verify.txt').read_text()
        if 'are compatible' not in pr or 'All DCPS provided to pr_verify have locked static' not in pr:
            raise ValueError('Missing full PR compatibility and locked-static proof')
        sites = set(re.findall(r'Static site (SLICE_X\d+Y\d+)', pr))
        # Include historical warning sites in this same pass, rather than
        # reopening both large checkpoints in a second review controller.
        baseline_pr = baseline.parent / 'dfx-flow/pr-verify.txt'
        if not baseline_pr.exists():
            baseline_pr = baseline.parent / 'pr-verify.txt'
        if baseline_pr.exists():
            sites.update(re.findall(r'Static site (SLICE_X\d+Y\d+)', baseline_pr.read_text()))
        sites = sorted(sites)
        if sites:
            site_file = output / 'static-sites.txt'
            site_file.write_text('\n'.join(sites) + '\n')
            comparison = output / 'static-properties.txt'
            vivado('static-properties', 'inspect-static-config.tcl', baseline,
                   signed / 'clock-qualified.dcp', site_file, comparison)
            run('static-compare', ['python3', scripts / 'compare-static-config.py', comparison])
        report = (signed / 'timing-summary.txt').read_text().splitlines()
        heading = next(i for i, line in enumerate(report) if 'WNS(ns)' in line and 'TPWS(ns)' in line)
        values = report[heading + 2].split()
        timing = dict(zip(('wns', 'tns', 'whs', 'ths', 'wpws', 'tpws'),
                          (float(values[i]) for i in (0, 1, 4, 5, 8, 9))))
        if not all(math.isfinite(value) for value in timing.values()):
            raise ValueError('Non-finite timing report')
        if min(timing[k] for k in ('wns', 'whs', 'wpws')) < 0 or any(timing[k] != 0 for k in ('tns', 'ths', 'tpws')):
            raise ValueError(f'Timing did not close: {timing}')
        for root in (signed, signed / 'dfx-flow'):
            for name in ('standard-dfx-drc.txt', 'default-drc.txt', 'abstract-dfx-drc.txt'):
                if re.search(r'\bError\b', (root / name).read_text()):
                    raise ValueError(f'DRC error in {root / name}')
        waveform = (signed / 'gated-clock-waveform-check.txt').read_text()
        if re.search(r'TIMING-5#|\|\s*TIMING-5\s*\|', waveform):
            raise ValueError('Invalid gated-clock waveform')
        clock_hz = args.frequency * 1000000
        host_clock = re.search(r'^host_clock\s+([0-9.]+)\s+\{', (signed / 'clocks.txt').read_text(), re.M)
        if not host_clock or abs(float(host_clock[1]) - 1e9 / clock_hz) >= .001:
            raise ValueError('Clock report differs from the runtime frequency')
        image = (signed / 'partial.pdi').read_bytes()
        package = output / 'firesim.vbin'
        images = clocks = 0
        names = set()
        with tarfile.open(source_vbin, 'r:gz') as source, tarfile.open(package, 'w:gz') as target:
            for member in source.getmembers():
                path = Path(member.name)
                if path.is_absolute() or '..' in path.parts or path.as_posix() != member.name or member.name in names:
                    raise ValueError(f'Unsafe or duplicate archive member: {member.name}')
                names.add(member.name)
                if not member.isfile():
                    raise ValueError(f'Unexpected archive member type: {member.name}')
                content = source.extractfile(member).read()
                if member.name.endswith('.pdi'):
                    images += 1
                    content = image
                    member.name = 'images/firesim-' + hashlib.sha256(image).hexdigest() + '.pdi'
                elif member.name == 'system_map.xml':
                    root = ET.fromstring(content)
                    entries = list(root.iter('ClockFrequency'))
                    clocks += len(entries)
                    for entry in entries:
                        entry.text = str(clock_hz)
                    content = ET.tostring(root, encoding='utf-8', xml_declaration=True)
                member.size = len(content)
                target.addfile(member, io.BytesIO(content))
        if images != 1 or clocks != 1:
            raise ValueError(f'Expected one image and clock entry, found {images}/{clocks}')
        (output / header.name).write_bytes(header.read_bytes())
        artifacts = [package, output / header.name]
        source_probes = build / 'firesim.vbin.prj/images/top_i_slash_slash_firesim_inst_0_hw_probes.ltx'
        debug_requested = any('axis_ila' in script.read_text() for script in (build / 'design').glob('*.ipgen.tcl'))
        probe_cores = []
        if source_probes.exists() and source_probes.stat().st_size:
            probe_layout = json.loads(source_probes.read_text())
            probe_cores = [core for block in probe_layout['ltx_root']['ltx_data']
                           for core in block.get('debug_cores', [])]
        if debug_requested and not probe_cores:
            raise ValueError('The AXIS ILA build has no complete probe metadata')
        if probe_cores:
            derivation_path = source_probes.with_suffix('.derivation.json')
            derivation = json.loads(derivation_path.read_text())
            if derivation['routed_checkpoint_sha256'] != digest(routed) or derivation['debug_ltx_sha256'] != digest(source_probes):
                raise ValueError('Debug probes are not bound to this routed checkpoint')
            if {core['uuid'] for core in probe_cores} != {core['uuid'] for core in derivation['routed_identities'].values()}:
                raise ValueError('Debug core identities differ from the routed design')
            debug_probes = output / 'debug.ltx'
            debug_probes.write_bytes(source_probes.read_bytes())
            debug_derivation = output / 'debug-probes-derivation.json'
            debug_derivation.write_bytes(derivation_path.read_bytes())
            artifacts.extend([debug_probes, debug_derivation])
        proof_files = sorted(signed.rglob('*.txt'))
        if (output / 'static-properties.json').exists():
            proof_files.append(output / 'static-properties.json')
        manifest = dict(schema_version=1, clock_hz=clock_hz, timing=timing,
                        reports=[dict(path=str(p.relative_to(output)), sha256=digest(p)) for p in proof_files],
                        shell_sha256=digest(shell), static_baseline_sha256=digest(baseline),
                        rtl_sha256=digest(rtl), native_driver_board_validated=False,
                        qualified_checkpoint_sha256=digest(signed / 'clock-qualified.dcp'),
                        static_review_sites=sites,
                        artifacts=[dict(path=p.name, sha256=digest(p))
                                   for p in artifacts])
        (output / 'build-qualification.json').write_text(json.dumps(manifest, indent=2) + '\n')
        status('offline-build-qualified', 'completed', hardware_tested=False)
        (output / 'result.json').write_text(json.dumps(dict(exit_code=0, elapsed_seconds=time.monotonic()-started, hardware_access=False)) + '\n')
    except Exception as error:
        stage = json.loads((output / 'status.json').read_text()).get('stage', 'setup') if (output / 'status.json').exists() else 'setup'
        status(stage, 'failed', error=str(error))
        (output / 'result.json').write_text(json.dumps(dict(exit_code=1, elapsed_seconds=time.monotonic()-started, hardware_access=False, error=str(error))) + '\n')
        raise


if __name__ == '__main__':
    main()
