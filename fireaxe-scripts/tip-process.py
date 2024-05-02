#!/usr/bin/env python3



import pandas as pd
import argparse
import re


parser = argparse.ArgumentParser(description="Arguments for processing TIP outputs")
parser.add_argument("--tip-output", type=str, required=True, help="Abs path to TIP output")
parser.add_argument("--tip-objdump", type=str, required=True, help="Abs path to TIP objdump")
args = parser.parse_args()





inst_type_map = {
    'lb': 'load',
    'lh': 'load',
    'lw': 'load',
    'lbu': 'load',
    'lhu': 'load',
    'ld': 'load',
    'fld': 'load',
    'flw': 'load',

    'sb': 'store',
    'sh': 'store',
    'sw': 'store',
    'sd': 'store',
    'fsd': 'store',
    'fsw': 'store',

    'sll': 'alu',
    'slli': 'alu',
    'slliw': 'alu',
    'srl': 'alu',
    'srli': 'alu',
    'sra': 'alu',
    'srai': 'alu',
    'sext.w': 'alu',

    'add': 'alu',
    'addi': 'alu',
    'addiw': 'alu',
    'sub': 'alu',
    'lui': 'alu',
    'auipc': 'alu',

    'mv': 'alu',
    'li': 'alu',

    'xor': 'alu',
    'xori': 'alu',
    'or': 'alu',
    'ori': 'alu',
    'and': 'alu',
    'andi': 'alu',

    'slt': 'alu',
    'slti': 'alu',
    'sltu': 'alu',
    'sltiu': 'alu'
}


class PipelineStats:
# time        : Number of cycles consumed by the current instruction (cycles that aren't hidden by pipelining)
# committing  : Cycles doing actual useful work
# stalling    : Pipeline stalled by the instruction due to long latency ops (e.g., d-cache miss)
# deferred    : Icache-miss
# rollingback : Cycles spent rolling back the pipeline
# exception   : Instruction that caused an exception flushes the pipeline. time when ROB is empty due to this flush before the exception handler instructions are filled in.
# misspeculated : Instruction that entered the ROB due to misspeculation
# flushes     : Cycles between when the non-speculative instructions before a branch are finished & the frontend fetches new instructions leading to the ROB being empty.
  time:     float
  commit:   int
  stall:    int
  deferred: int
  rollback: int
  xcption:  int
  misspec:  int
  flush:    int

  def __init__(self, line: str):
    words = line.split(';')
    self.time     = float(words[1])
    self.commit   = int(words[2])
    self.stall    = int(words[3])
    self.deferred = int(words[4])
    self.rollback = int(words[5])
    self.xcption  = int(words[6])
    self.misspec  = int(words[7])
    self.flush   = int(words[8])

  def add(self, line:str):
    words = line.split(';')
    self.time     += float(words[1])
    self.commit   += int(words[2])
    self.stall    += int(words[3])
    self.deferred += int(words[4])
    self.rollback += int(words[5])
    self.xcption  += int(words[6])
    self.misspec  += int(words[7])
    self.flush    += int(words[8])

  def print(self):
    print(self.time, self.commit, self.stall, self.deferred, self.rollback, self.xcption, self.misspec, self.flush)

def get_pc_stats():
  stats = {}
  with open(args.tip_output, 'r') as f:
    lines = f.readlines()
    lines.pop(0)
    for line in lines:
      pc = line.split(';')[0][2:]
      if pc in stats.keys():
        stats[pc].add(line)
      else:
        stats[pc] = PipelineStats(line)
  return stats

def parse_objdump():
  inst_type = {}
  with open(args.tip_objdump, 'r') as f:
    lines = f.readlines()
    for line in lines:
      words = re.split(r'\t+|\s+|\n+', line)
      # line starts with space, instruction
      if re.match(r'\s', line) and len(words) >= 4:
        pc = words[1][:-1]
        inst = words[3]
        inst_type[pc] = inst
  return inst_type


def process_tip_out():
  pc_stats = get_pc_stats()
  pc_inst = parse_objdump()


  time = 0.0
  commit = 0
  ld_stall = 0
  st_stall = 0
  alu_stall = 0
  misc_stall = 0
  deferred = 0
  rollback = 0
  xcption = 0
  misspec = 0
  flush = 0
  for (pc, stats) in pc_stats.items():
    if pc not in pc_inst.keys():
      continue

    inst = pc_inst[pc]
    if inst in inst_type_map.keys():
      inst_type = inst_type_map[inst]
    else:
      inst_type = 'misc'

# print(pc, inst, inst_type)

    time += stats.time
    if inst_type == 'load':
      ld_stall += stats.stall
    elif inst_type == 'store':
      st_stall += stats.stall
    else:
      # print(f"inst {inst}, pc {pc}")
      alu_stall += stats.stall
    deferred += stats.deferred
    rollback += stats.rollback
    xcption += stats.xcption
    misspec += stats.misspec
    flush += stats.flush

    actual_commit = stats.time - stats.stall - stats.deferred - stats.rollback - stats.xcption - stats.misspec - stats.flush
    commit += actual_commit

  total = commit + ld_stall + st_stall + alu_stall + misc_stall + deferred + rollback + misspec + flush
  commit_f = commit / total
  ld_stall_f = ld_stall / total
  st_stall_f = st_stall / total
  alu_stall_f = alu_stall / total
  misc_stall_f = misc_stall / total
  frontend_f = deferred / total
  rollback_f = rollback / total
  xcption_f = xcption / total
  misspec_f = misspec / total
  flush_f = flush / total

  misc_f = rollback_f + xcption_f + flush_f
  print('time, commit, ld_stall, st_stall, alu_stall, frontend, misspec, misc')
  print(time, commit_f, ld_stall_f, st_stall_f, alu_stall_f, frontend_f, misspec_f, misc_f)

if __name__ == '__main__':
    process_tip_out()
