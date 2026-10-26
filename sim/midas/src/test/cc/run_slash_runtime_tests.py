#!/usr/bin/env python3
"""Run bounded CPU-only SLASH runtime checks without accessing a board."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--source-root', type=Path, default=Path(__file__).resolve().parents[5])
parser.add_argument('--legacy-runtime', action='store_true', help='Compare the historical xilinx_v80 runtime; not available in official main')
parser.add_argument('--sdk-prefix', type=Path, help='Also compile with real SDK headers, without executing or linking it')
parser.add_argument('--dependency-prefix', type=Path, default=os.environ.get('CONDA_PREFIX'), help='Prefix with SDK dependency headers (JSON, libxml2 and ZMQ)')
args = parser.parse_args()
review = Path(__file__).resolve().parent
root = args.source_root.resolve()
candidate = not args.legacy_runtime
source = root / 'sim/midas/src/main/cc'
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
deadline = time.monotonic() + 180

def run(command, work, budget):
    (work / 'command.json').write_text(json.dumps(command, indent=2) + '\n')
    with (work / 'stdout.log').open('w') as stdout, (work / 'stderr.log').open('w') as stderr:
        process = subprocess.Popen(command, cwd=work, stdout=stdout, stderr=stderr,
            start_new_session=True,
            env=dict(os.environ, ASAN_OPTIONS='detect_leaks=1:detect_stack_use_after_return=1'))
        try:
            return process.wait(timeout=max(0.1, min(budget, deadline - time.monotonic())))
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            return 124

sdk = output / 'model-include'
(sdk / 'vrt/allocator').mkdir(parents=True)
for header in ('device.hpp', 'kernel.hpp', 'buffer.hpp', 'allocator/allocator.hpp'):
    (sdk / 'vrt' / header).write_text('#include "' + str(review / 'slash_runtime/vrt_model.hpp') + '"\n')
(sdk / 'FireSim-generated.const.h').write_text('// CPU audit: stream metadata intentionally disabled.\n')
runtime = source / ('simif_xilinx_alveo_v80_slash.cc' if candidate else 'simif_xilinx_v80.cc')
files = sorted(set(source.rglob('*.h')) | {runtime,
    review / 'slash_runtime/runtime_audit.cc', review / 'slash_runtime/vrt_model.hpp',
    review / 'slash_runtime/stream_commands.cc', Path(__file__)})
manifest = {
    'started_at_utc': datetime.now(timezone.utc).isoformat(),
    'source_commit': subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'], text=True).strip(),
    'source_status': subprocess.check_output(['git','-C',str(root),'status','--porcelain'], text=True),
    'sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
    'total_timeout_seconds': 180, 'build_timeout_seconds': 60, 'case_timeout_seconds': 12,
    'randomness': 'none', 'hardware_access': False,
    'candidate_runtime': candidate,
    'model': 'Sparse predicted card memory, contiguous host vectors, bounded region allocator; no libvrt linked',
    'limits': 'CPU allocation/control/capture and command helpers; synthetic metadata only, DMA hardware and RTL not modeled',
}
(output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
binary = output / 'runtime_audit'
command = ['g++','-std=c++20','-O1','-g','-fsanitize=address,undefined',
    '-fno-omit-frame-pointer','-fno-pie','-no-pie','-I'+str(sdk),'-I'+str(source),
    '-DV80_RUNTIME_SOURCE="'+str(runtime)+'"',
    str(review / 'slash_runtime/runtime_audit.cc'),'-o',str(binary)]
if candidate:
    command.append('-DCANDIDATE_RUNTIME')
result = {'hardware_access': False, 'build_exit_code': run(command, output, 60), 'cases': {}}
if result['build_exit_code'] == 0:
    for case in ('single','copy-ownership','same-offset-shorter','reverse-overlap',
                 'ascending-overlap','zero-length','aperture-limit','address-overflow',
                 'flush-before-read','flush-before-write','control-range','control-alignment',
                 'split-512m','tail-1g','ring-alignment','ring-sync','ring-exhaustion',
                 'growth-after-rings','ring-zero-size',
                 'allocation-failure-null','allocation-failure-throw',
                 'regrow-failure','second-region-failure','wrong-target-base',
                 'nonconsecutive-regions','stream-allocation-failure',
                 'stream-outside-aperture','stream-sync-range','regrow-overlap',
                 'design-bulk-limit','design-bulk-last','design-small','stream-outside-design',
                 'stream-crosses-design','stream-last-design-pool'):
        work = output / case
        work.mkdir()
        code = run([str(binary),case], work, 12)
        result['cases'][case] = code
        print(f'{case}: {"PASS" if code == 0 else "FAIL"} ({code})', flush=True)
        if code == 124:
            break
if candidate:
    if result['build_exit_code'] == 0:
        for case in ('control-reserved', 'control-upper-valid',
                     'control-fence-invalid', 'control-fence-valid',
                     'no-ddr-control', 'no-ddr-reservation', 'invalid-memory-config'):
            work = output / case
            work.mkdir()
            code = run([str(binary), case], work, 12)
            result['cases'][case] = code
            print(f'{case}: {"PASS" if code == 0 else "FAIL"} ({code})', flush=True)
            if code == 124:
                break
    work = output / 'ddr-runtime'
    work.mkdir()
    metadata = '#define V80_DDR_STREAM_METADATA\n#define V80_DDR_STREAM_MIN_DDR 4194304\n'
    for index, key in enumerate(('DDR_LO','DDR_HI','STREAM','BYTES','COMMAND','STATUS')):
        metadata += f'#define V80_DDR_STREAM_{key} {256 + index * 4}\n'
    (work / 'FireSim-generated.const.h').write_text(metadata)
    ddr_binary = work / 'runtime_audit'
    command = ['g++','-std=c++20','-O1','-g','-fsanitize=address,undefined',
        '-fno-omit-frame-pointer','-fno-pie','-no-pie','-I'+str(work),'-I'+str(sdk),'-I'+str(source),
        '-DV80_RUNTIME_SOURCE="'+str(runtime)+'"','-DCANDIDATE_RUNTIME',
        str(review / 'slash_runtime/runtime_audit.cc'),'-o',str(ddr_binary)]
    result['ddr_build_exit_code'] = run(command, work, 30)
    result['ddr_cases'] = {}
    if result['ddr_build_exit_code'] == 0:
        for case in ('cpu-stream-zero','cpu-stream-invalid','cpu-stream-push',
                     'cpu-stream-pull','cpu-stream-pending-target','no-ddr-control'):
            case_work = work / case
            case_work.mkdir()
            code = run([str(ddr_binary), case], case_work, 12)
            result['ddr_cases'][case] = code
            print(f'{case}: {"PASS" if code == 0 else "FAIL"} ({code})', flush=True)
            if code == 124:
                break
    work = output / 'mmio-runtime'
    work.mkdir()
    metadata = '#define V80_MMIO_STREAM_METADATA\n'
    for index, key in enumerate(('ADDRESS','TX','READ_WORDS','RX')):
        metadata += f'#define V80_MMIO_STREAM_{key} {256 + index * 4}\n'
    (work / 'FireSim-generated.const.h').write_text(metadata)
    mmio_binary = work / 'runtime_audit'
    command = ['g++','-std=c++20','-O1','-g','-fsanitize=address,undefined',
        '-fno-omit-frame-pointer','-fno-pie','-no-pie','-I'+str(work),'-I'+str(sdk),'-I'+str(source),
        '-DV80_RUNTIME_SOURCE="'+str(runtime)+'"','-DCANDIDATE_RUNTIME',
        str(review / 'slash_runtime/runtime_audit.cc'),'-o',str(mmio_binary)]
    result['mmio_build_exit_code'] = run(command, work, 30)
    result['mmio_cases'] = {}
    if result['mmio_build_exit_code'] == 0:
        for case in ('no-ddr-control','no-ddr-reservation','invalid-memory-config'):
            case_work = work / case
            case_work.mkdir()
            result['mmio_cases'][case] = run([str(mmio_binary),case],case_work,12)
    work = output / 'stream-helpers'
    work.mkdir()
    metadata = '#define V80_DDR_STREAM_METADATA\n#define V80_MMIO_STREAM_METADATA\n#define V80_DDR_STREAM_MIN_DDR 1073741824\n'
    for prefix, keys in (('DDR', ('DDR_LO','DDR_HI','STREAM','BYTES','COMMAND','STATUS')),
                         ('MMIO', ('ADDRESS','TX','READ_WORDS','RX'))):
        for index, key in enumerate(keys):
            metadata += f'#define V80_{prefix}_STREAM_{key} {256 + (prefix == "MMIO") * 64 + index * 4}\n'
    (work / 'FireSim-generated.const.h').write_text(metadata)
    helper = work / 'stream_commands'
    command = ['g++','-std=c++20','-O1','-g','-fsanitize=address,undefined',
        '-fno-omit-frame-pointer','-fno-pie','-no-pie','-I'+str(work),'-I'+str(source),
        str(review / 'slash_runtime/stream_commands.cc'),'-o',str(helper)]
    legacy_clock = 'class Clock' not in (source / 'core/v80_slash_ddr_stream.h').read_text()
    if legacy_clock:
        command.append('-DSLASH_HELPER_LEGACY_CLOCK')
        result['helper_skips'] = {'ddr-timeout': 'Historical helper has no injectable clock; real 60-second busy wait intentionally not run'}
    result['helper_build_exit_code'] = run(command, work, 30)
    result['helper_cases'] = {}
    if result['helper_build_exit_code'] == 0:
        for case in ('ddr-push','ddr-pull','ddr-error','ddr-error-done','ddr-timeout','ddr-zero',
                     'ddr-invalid-low','ddr-invalid-alignment','ddr-invalid-stream',
                     'ddr-invalid-token','ddr-invalid-capacity','ddr-invalid-wrap',
                     'mmio-push','mmio-pull','mmio-zero','mmio-invalid','mmio-null'):
            if legacy_clock and case == 'ddr-timeout':
                continue
            case_work = work / case
            case_work.mkdir()
            code = run([str(helper), case], case_work, 12)
            result['helper_cases'][case] = code
            print(f'{case}: {"PASS" if code == 0 else "FAIL"} ({code})', flush=True)
            if code == 124:
                break
if args.sdk_prefix:
    assert candidate
    prefix = args.sdk_prefix.resolve()
    if not args.dependency_prefix:
        parser.error('--dependency-prefix or CONDA_PREFIX is required with --sdk-prefix')
    conda = Path(args.dependency_prefix).resolve()
    result['real_sdk_compile'] = {}
    for variant in ('none', 'mmio', 'ddr'):
        work = output / ('real-sdk-' + variant)
        work.mkdir()
        metadata = '// Compile fixture only; not a generated FPGA address map.\n'
        if variant == 'mmio':
            metadata += '#define V80_MMIO_STREAM_METADATA\n'
            for index, key in enumerate(('ADDRESS','TX','READ_WORDS','RX')):
                metadata += f'#define V80_MMIO_STREAM_{key} {index * 4}\n'
        elif variant == 'ddr':
            metadata += '#define V80_DDR_STREAM_METADATA\n#define V80_DDR_STREAM_MIN_DDR 1073741824\n'
            for index, key in enumerate(('DDR_LO','DDR_HI','STREAM','BYTES','COMMAND','STATUS')):
                metadata += f'#define V80_DDR_STREAM_{key} {index * 4}\n'
        (work / 'FireSim-generated.const.h').write_text(metadata)
        compile_command = ['g++','-std=c++20','-O1','-c','-I'+str(work),'-I'+str(source),
            '-idirafter',str(conda / 'include'),'-idirafter',str(conda / 'include/libxml2'),
            '-idirafter',str(prefix / 'include'),str(runtime),'-o',str(work / 'runtime.o')]
        code = run(compile_command, work, 30)
        result['real_sdk_compile'][variant] = code
        print(f'real-sdk-{variant}: {"PASS" if code == 0 else "FAIL"} ({code})', flush=True)
    sdk_files = sorted((prefix / 'include').rglob('*.hpp'))
    (output / 'real-sdk-fingerprints.json').write_text(json.dumps({
        'sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sdk_files},
        'note': 'Actual installed SDK headers; no SDK function was executed.',
    }, indent=2) + '\n')
result['source_sha256_match_after_run'] = all(
    hashlib.sha256(Path(p).read_bytes()).hexdigest() == digest for p,digest in manifest['sha256'].items())
(output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result), flush=True)
raise SystemExit(int(result['build_exit_code'] != 0 or any(result['cases'].values())
                     or result.get('ddr_build_exit_code', 0) != 0
                     or any(result.get('ddr_cases', {}).values())
                     or result.get('mmio_build_exit_code', 0) != 0
                     or any(result.get('mmio_cases', {}).values())
                     or result.get('helper_build_exit_code', 0) != 0
                     or any(result.get('helper_cases', {}).values())
                     or any(result.get('real_sdk_compile', {}).values())
                     or not result['source_sha256_match_after_run']))
