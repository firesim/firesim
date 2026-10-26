#!/usr/bin/env python3
"""Apply the pinned FireSim dependency patches to a clean SLASH checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def prepare(checkout):
    dependencies = Path(__file__).resolve().parent / 'dependencies'
    manifest = json.loads((dependencies / 'slash.json').read_text())
    def git(*args):
        return subprocess.check_output(['git', '-C', str(checkout), *args], text=True, timeout=30)
    if git('rev-parse', 'HEAD').strip() != manifest['base_commit']:
        raise ValueError('SLASH HEAD differs from the pinned dependency baseline')
    patches = []
    for entry in manifest['patches']:
        patch = dependencies / entry['path']
        if hashlib.sha256(patch.read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError(f'Dependency patch hash differs: {patch.name}')
        patches.append(str(patch))
    files = manifest['patched_files_sha256']
    changed = set(git('diff', '--name-only', 'HEAD').splitlines())
    unknown = git('ls-files', '--others', '--exclude-standard').splitlines()
    already = changed == set(files) and all(hashlib.sha256((checkout / p).read_bytes()).hexdigest() == h
                                           for p, h in files.items())
    if unknown or git('diff', '--cached', '--name-only').strip() or (changed and not already):
        raise ValueError('SLASH checkout has unrelated, staged, or unexpected changes')
    if not already:
        git('apply', '--check', *patches)
        git('apply', *patches)
    for path, digest in files.items():
        if hashlib.sha256((checkout / path).read_bytes()).hexdigest() != digest:
            raise ValueError(f'Patched SLASH source hash differs: {path}')
    print(json.dumps(dict(base_commit=manifest['base_commit'], patched_files_sha256=files,
                          already_applied=already, hardware_access=False), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('checkout', type=Path)
    args = parser.parse_args()
    prepare(args.checkout.resolve(strict=True))


if __name__ == '__main__':
    main()
