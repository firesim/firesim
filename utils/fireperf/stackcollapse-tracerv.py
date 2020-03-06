#!/usr/bin/env python3
#
# Collapse TracerV traces into single-line call stacks for flamegraph.pl
#

import argparse
import re
import sys

parser = argparse.ArgumentParser()
parser.add_argument('-t', '--time', action='store_true',
                    help='preserve time order; do not merge stacks')
parser.add_argument('infile', nargs='?', type=argparse.FileType('r'), default=sys.stdin)
args = parser.parse_args()

def warn(fmt, lineno, line, *args, **kwargs):
    print(sys.argv[0], fmt.format(*args, **kwargs),
        lineno, line.rstrip(), file = sys.stderr, sep = ': ')

def err(fmt, lineno, line, *args, **kwargs):
    warn(fmt, lineno, line, *args, **kwargs)
    exit(1)

class Folded:
    def __init__(self):
        self.data = {}

    def add(self, key, count):
        self.data[key] = self.data.get(key, 0) + count

    def print(self):
        for k, v in self.data.items():
            if k:
                print(k, v)

class Series:
    def add(self, key, count):
        if key:
            print(key, count)

    def print(self):
        pass

stack = []
data = Series() if args.time else Folded()
cycle = 0
pattern = re.compile('(\S+) label: <?(.+?)(?:>:)?,.*: (\d+)')

for lineno, line in enumerate(args.infile):
    m = pattern.search(line)
    if not m:
        continue

    tag = m.group(1)
    label = m.group(2)
    now = int(m.group(3))
    if now < cycle:
        warn('non-monotonic timestamps', lineno, line)
        continue
    key = ';'.join(stack)

    if tag == 'Start':
        stack.append(label)
    elif tag == 'End':
        if stack:
            start = stack.pop()
            if label != start:
                err('start/end mismatch <{}> <{}>', lineno, line, start, label)
        else:
            warn('stack underflow', lineno, line)
            continue
    else:
        err('invalid entry type "{}"', lineno, line, tag)

    data.add(key, now - cycle)
    cycle = now

data.print()
