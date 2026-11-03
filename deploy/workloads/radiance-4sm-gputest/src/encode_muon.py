#!/usr/bin/env python3
"""Minimal Muon (64-bit encoding) assembler for the GPU smoke test. Field positions from
radiance/muon/Decode.scala: opcode[8:0] (opcode 6:0 + opext 8:7), rd[16:9], f3[19:17], rs1[27:20],
rs2[35:28], rs3[43:36], f7[58:52], imm24[59:36], immHi: I2 type bits 35:28, S/B type bits 16:9."""
import struct, sys
def base(op, rd=0, f3=0, rs1=0, rs2=0, opext=0, f7=0):
    return (op & 0x7f) | ((opext & 3) << 7) | ((rd & 0xff) << 9) | ((f3 & 7) << 17) | ((rs1 & 0xff) << 20) | ((rs2 & 0xff) << 28) | ((f7 & 0x7f) << 52)
def i2(op, rd, f3, rs1, imm):      # I-type: 32-bit imm, hi byte in rs2 slot
    imm &= 0xffffffff
    return base(op, rd, f3, rs1) | ((imm >> 24) << 28) | ((imm & 0xffffff) << 36)
def addi(rd, rs1, imm):  return i2(0x13, rd, 0, rs1, imm)
def slli(rd, rs1, sh):   return i2(0x13, rd, 1, rs1, sh & 0x7f)
def csrrs(rd, csr, rs1): return i2(0x73, rd, 2, rs1, csr)
def add(rd, rs1, rs2):   return base(0x33, rd, 0, rs1, rs2)
def sw(rs2, imm, rs1):   # S-type: imm[31:24] in rd slot, imm[23:0] in imm24, opext 0 = .global
    imm &= 0xffffffff
    return base(0x23, (imm >> 24) & 0xff, 2, rs1, rs2) | ((imm & 0xffffff) << 36)
def tmc(rs1):            return base(0x0b, 0, 0, rs1)
RESULTS = 0x10100000          # Muon address; system address = 0x1_0000_0000 + this
MAGIC   = 0xC0DE0000
prog = [
    csrrs(5, 0xf14, 0),       # x5 = mhartid
    slli(6, 5, 2),            # x6 = hartid * 4
    addi(7, 0, RESULTS),      # x7 = &results[0]
    add(7, 7, 6),             # x7 = &results[hartid]
    addi(8, 0, MAGIC),        # x8 = MAGIC
    add(8, 8, 5),             # x8 = MAGIC | hartid
    sw(8, 0, 7),              # results[hartid] = MAGIC | hartid
    tmc(0),                   # retire the warp -> core "finished"
]
# sanity: reproduce words seen in the real kernel
assert csrrs(5, 0xf14, 0) == 0x0000f14000040a73, hex(csrrs(5, 0xf14, 0))
assert slli(7, 5, 4) == 0x0000004000520e13
assert add(6, 6, 7) == 0x0000000070600c33
assert sw(1, 0x1c, 2) == 0x000001c010240023
assert addi(13, 12, 3) == 0x0000003000c01a13
assert tmc(0) == 0x000000000000000b and tmc(5) == 0x000000000050000b
out = b"".join(struct.pack("<Q", w) for w in prog)
open(sys.argv[1] if len(sys.argv) > 1 else "gpu_kernel.bin", "wb").write(out)
for i, w in enumerate(prog): print(f"{0x10000000 + 8*i:08x}: {w:016x}")
print(f"{len(out)} bytes; results at Muon {RESULTS:#x} = system {0x100000000 + RESULTS:#x}")
