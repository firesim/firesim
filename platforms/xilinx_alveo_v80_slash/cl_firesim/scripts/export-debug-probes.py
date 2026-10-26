#!/usr/bin/env python3
"""Export V80 probe layouts and bind them to the full routed core identities.

The SLASH shell is imported as a checkpoint without its IPI hardware definition.
Vivado 2025.1 crashes in HSI when exporting its complete AXI debug probe file.
The kernel IP checkpoint can export its probe descriptions normally. Rebase
those descriptions to the hierarchy and UUIDs read from the original routed
design, retaining the derivation alongside the LTX. Board discovery and capture
are separate acceptance requirements.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rebase(layout, identities):
    cores = [core for block in layout['ltx_root']['ltx_data'] for core in block.get('debug_cores', [])]
    if not cores or len(cores) != len(identities):
        raise ValueError('IP and routed debug core counts differ or are empty')
    mapping, prefixes = {}, set()
    for core in cores:
        matches = [name for name in identities if name.endswith('/' + core['name'])]
        if len(matches) != 1:
            raise ValueError(f'Ambiguous routed identity for {core["name"]}: {matches}')
        full = matches[0]
        mapping[core['name']] = full
        prefixes.add(full[:-len(core['name'])])
        count = identities[full].get('probe_count')
        if count is not None and count != len(core.get('pins', [])):
            raise ValueError(f'Probe count changed for {full}')
    if len(prefixes) != 1:
        raise ValueError('Expected one kernel hierarchy prefix')
    prefix = prefixes.pop()

    def nets(value):
        if isinstance(value, dict):
            return {key: prefix + item if key == 'name' else nets(item) for key, item in value.items()}
        if isinstance(value, list):
            return [nets(item) for item in value]
        return value

    changes = []
    for core in cores:
        before = dict(name=core['name'], uuid=core['uuid'])
        core['name'] = mapping[core['name']]
        core['uuid'] = identities[core['name']]['uuid']
        if not core['name'].startswith('top_i/slash/'):
            raise ValueError('Debug core is outside the SLASH reconfigurable partition')
        core['reconfigTop'] = 'top_i/slash'
        if 'master' in core:
            core['master'] = mapping[core['master']]
        for pin in core.get('pins', []):
            pin['nets'] = nets(pin['nets'])
        changes.append(dict(source=before, routed=dict(name=core['name'], uuid=core['uuid'])))
    return changes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ip-checkpoint', type=Path, required=True)
    parser.add_argument('--routed-checkpoint', type=Path, required=True)
    parser.add_argument('--identities', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--vivado', default='vivado')
    args = parser.parse_args()
    ip, routed, records = (path.resolve(strict=True) for path in
                           (args.ip_checkpoint, args.routed_checkpoint, args.identities))
    output = args.output.resolve()
    work = output.parent / (output.stem + '-export')
    work.mkdir(parents=True, exist_ok=True)
    identities = {}
    for line in records.read_text().splitlines():
        fields = line.split('\t')
        if len(fields) not in (2, 3) or not re.fullmatch('[0-9A-Fa-f]{32}', fields[1]):
            raise ValueError(f'Invalid routed debug identity: {line!r}')
        name, uuid = fields[:2]
        if name in identities:
            raise ValueError(f'Duplicate routed debug core: {name}')
        identities[name] = dict(uuid=uuid.upper())
        if len(fields) == 3 and fields[2]:
            identities[name]['probe_count'] = int(fields[2])
    raw = work / 'ip-probes.ltx'
    with (work / 'export.log').open('w') as log:
        subprocess.run([args.vivado, '-mode', 'batch', '-source',
                        Path(__file__).with_name('export-debug-ip.tcl'), '-tclargs', ip, raw],
                       cwd=work, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=900)
    layout = json.loads(raw.read_text())
    changes = rebase(layout, identities)
    temporary = output.with_suffix('.ltx.tmp')
    temporary.write_text(json.dumps(layout, indent=2) + '\n')
    temporary.replace(output)
    manifest = dict(method='IP probe descriptions rebased to original routed core properties',
                    ip_checkpoint=str(ip), ip_checkpoint_sha256=digest(ip),
                    routed_checkpoint=str(routed), routed_checkpoint_sha256=digest(routed),
                    routed_identities=identities, ip_probes_sha256=digest(raw),
                    debug_ltx_sha256=digest(output), changes=changes,
                    board_discovery_and_capture_required=True)
    output.with_suffix('.derivation.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Exported {len(changes)} routed debug cores to {output}')


if __name__ == '__main__':
    main()
