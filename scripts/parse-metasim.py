#!/usr/bin/env python3

import os

A = '/scratch/joonho.whangbo/coding/firesim-out-multifpga/fame5-dualrocket-40'
B = '/scratch/joonho.whangbo/coding/firesim-out-multifpga/ci-rocket-dual-tile-2fpga'


DIFF = 20


def collect_tokens(filename):
  out_tokens = []
  in_tokens = []

  with open(filename) as f:
    lines = f.readlines()
    for line in lines:
      words = line.split()
      if len(words) < 5:
        continue
      if (words[2] == 'slicer') and ('token' in words[3]):
        out_tokens.append(words[4])
      elif (words[2] == 'aggr') and ('token' in words[3]):
        in_tokens.append(words[4])
  return (out_tokens, in_tokens)


def compare_tokens(a, b):
  print(f'comparing {a} vs {b}')
  (ao, ai) = collect_tokens(a)
  (bo, bi) = collect_tokens(b)

  print(f'comparing output tokens')
  diff = 0
  for (idx, (aot, bot)) in enumerate(zip(ao, bo)):
    if aot != bot:
      print(f'idx: {idx} {aot} != {bot}')
      diff += 1
    if diff > DIFF:
      break

  print(f'comparing input tokens')
  diff = 0
  for (idx, (ait, bit)) in enumerate(zip(ai, bi)):
    if ait != bit:
      print(f'idx: {idx} {ait} != {bit}')
      diff += 1
    if diff > DIFF:
      break

def compare_sim_slots(a, b, slotno):
  a_metasim_stderr = os.path.join(a, f'sim_slot_{slotno}', 'metasim_stderr.out')
  b_metasim_stderr = os.path.join(b, f'sim_slot_{slotno}', 'metasim_stderr.out')
  compare_tokens(a_metasim_stderr, b_metasim_stderr)

def main():
  compare_sim_slots(A, B, 0)
# compare_sim_slots(A, B, 1)



if __name__ == "__main__":
  main()
