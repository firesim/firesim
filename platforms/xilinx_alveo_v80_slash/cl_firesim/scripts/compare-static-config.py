#!/usr/bin/env python3
"""Fail closed when selected static-site programming differs or is incomplete."""
import argparse
import json
from pathlib import Path


def compare(path):
    groups = {}
    for number, line in enumerate(path.read_text().splitlines(), 1):
        fields = line.split('|', 4)
        if len(fields) != 5 or not all(fields[:4]):
            raise ValueError(f'Invalid static property record at line {number}')
        checkpoint, site, cell, prop, value = fields
        entries = groups.setdefault(checkpoint, {})
        key = (site, cell, prop)
        if key in entries:
            raise ValueError(f'Duplicate static property record at line {number}')
        entries[key] = value
    if len(groups) != 2:
        raise ValueError('Expected nonempty property records from two distinct checkpoints')
    initial, additional = groups.values()
    differences = [dict(site=k[0], cell=k[1], property=k[2],
                        initial=initial.get(k), additional=additional.get(k))
                   for k in sorted(initial.keys() | additional.keys()) if initial.get(k) != additional.get(k)]
    return dict(checkpoints=list(groups), entries_initial=len(initial),
                entries_additional=len(additional), differences=differences)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('records', type=Path)
    args = parser.parse_args()
    result = compare(args.records)
    args.records.with_suffix('.json').write_text(json.dumps(result, indent=2) + '\n')
    print(f'Static configuration entries: {result["entries_initial"]}/{result["entries_additional"]}; '
          f'differences: {len(result["differences"])}')
    raise SystemExit(bool(result['differences']))


if __name__ == '__main__':
    main()
