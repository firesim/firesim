#!/usr/bin/env python3
"""Compare real SDK buddy allocation before/after the pinned patch, on CPU."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk-checkout', type=Path, required=True)
    parser.add_argument('--vrt-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cxx', default='g++')
    parser.add_argument('--timeout-seconds', type=int, default=120)
    args = parser.parse_args()
    if args.timeout_seconds <= 0:
        parser.error('timeout must be positive')
    platform = Path(__file__).resolve().parents[1]
    sdk = args.sdk_checkout.resolve(strict=True)
    prefix = args.vrt_home.resolve(strict=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    dependency = json.loads((platform / 'dependencies/slash.json').read_text())
    header = 'vrt/include/vrt/allocator/allocator.hpp'
    source = Path(__file__).with_name('sdk_allocator_test.cc')
    fixed_header = prefix / 'include/vrt/allocator/allocator.hpp'
    if hashlib.sha256(fixed_header.read_bytes()).hexdigest() != dependency['patched_files_sha256'][header]:
        raise ValueError('Installed allocator header differs from the pinned patched source')
    baseline = output / 'baseline-include/vrt/allocator/allocator.hpp'
    baseline.parent.mkdir(parents=True)
    baseline.write_bytes(subprocess.check_output(['git', '-C', str(sdk), 'show',
                          dependency['base_commit'] + ':' + header], timeout=30))
    started = time.monotonic()
    results, commands = [], []
    manifest = dict(sdk_base_commit=dependency['base_commit'], hardware_access=False,
                    timeout_seconds=args.timeout_seconds, source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                    baseline_header_sha256=hashlib.sha256(baseline.read_bytes()).hexdigest(),
                    fixed_header_sha256=hashlib.sha256(fixed_header.read_bytes()).hexdigest(),
                    sdk_libraries_sha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                          for p in (prefix / 'lib').glob('*.so*') if p.is_file()})
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    env = dict(os.environ, LD_LIBRARY_PATH=str(prefix / 'lib') + ':' + os.environ.get('LD_LIBRARY_PATH', ''),
               ASAN_OPTIONS='abort_on_error=1:detect_leaks=1', UBSAN_OPTIONS='halt_on_error=1')
    def run(name, command):
        commands.append(dict(name=name, command=command))
        (output / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        with (output / (name + '.stdout.log')).open('w') as out, (output / (name + '.stderr.log')).open('w') as err:
            remaining = args.timeout_seconds - (time.monotonic() - started)
            if remaining <= 0:
                raise TimeoutError('SDK CPU check deadline expired')
            process = subprocess.Popen(command, stdout=out, stderr=err, env=env, start_new_session=True)
            try:
                return process.wait(timeout=min(30, remaining))
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                raise TimeoutError(f'SDK CPU check timed out: {name}')
    code = 1
    try:
        for version in ('baseline', 'fixed'):
            include = output / 'baseline-include' if version == 'baseline' else prefix / 'include'
            executable = output / version
            command = [args.cxx, '-std=c++20', '-O1', '-g', '-fsanitize=address,undefined',
                       '-fno-omit-frame-pointer', '-fno-pie', '-no-pie', '-I' + str(include),
                       '-I' + str(prefix / 'include'), str(source), '-L' + str(prefix / 'lib'),
                       '-Wl,-rpath,' + str(prefix / 'lib'), '-lvrt', '-o', str(executable)]
            if run(version + '-compile', command):
                raise RuntimeError(f'{version} compile failed')
            for scenario in ('empty-large', 'empty-medium', 'split-exhaustion', 'oversized-control'):
                name = version + '-' + scenario
                result = run(name, [str(executable), scenario])
                expected = int(version == 'baseline' and scenario != 'oversized-control')
                results.append(dict(version=version, scenario=scenario, exit_code=result, expected=expected))
                if result != expected:
                    raise RuntimeError(f'Unexpected {name} exit status: {result}')
                if expected and 'exhaustion returned a null slice instead of bad_alloc' not in (output / (name + '.stderr.log')).read_text():
                    raise RuntimeError(f'{name} failed for a different reason')
        code = 0
    finally:
        result = dict(exit_code=code, elapsed_seconds=time.monotonic() - started, hardware_access=False, checks=results)
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)
    raise SystemExit(code)


if __name__ == '__main__':
    main()
