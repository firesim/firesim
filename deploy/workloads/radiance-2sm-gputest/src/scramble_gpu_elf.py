#!/usr/bin/env python3
"""Rewrite a Radiance .soc.elf so that its GPU-memory LOAD segments are already in the
hashed DRAM layout (radiance AddressHashParams.forward), for loaders that write DRAM
directly (FireSim LoadMem, +loadmem). Non-GPU segments, section headers and symbols are kept.
usage: scramble_gpu_elf.py in.elf out.elf [--base 0x100000000] [--size 0x80000000] [--unit 32] [--slices 4] [--check]"""
import argparse, struct, sys

def fold(o, slice_bits, nslices):
    acc = 0
    while o:
        acc ^= o & (nslices - 1); o >>= slice_bits
    return acc

def forward(a, base, size, unit, nslices):
    if not (base <= a < base + size): return a
    range_bits = size.bit_length() - 1; unit_bits = unit.bit_length() - 1; slice_bits = nslices.bit_length() - 1
    off = a - base; byte = off & (unit - 1); v = off >> unit_bits; o = v >> slice_bits
    c = (v & (nslices - 1)) ^ fold(o, slice_bits, nslices)
    return base + (c << (range_bits - slice_bits)) + (o << unit_bits) + byte

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("inp"); ap.add_argument("out")
    ap.add_argument("--base", type=lambda x: int(x, 0), default=0x1_0000_0000)
    ap.add_argument("--size", type=lambda x: int(x, 0), default=0x8000_0000)
    ap.add_argument("--unit", type=int, default=32); ap.add_argument("--slices", type=int, default=4)
    ap.add_argument("--check", action="store_true"); a = ap.parse_args()
    d = bytearray(open(a.inp, "rb").read())
    assert d[:4] == b"\x7fELF" and d[4] == 2 and d[5] == 1, "need ELF64 little-endian"
    e_phoff, = struct.unpack_from("<Q", d, 0x20); e_phentsize, e_phnum = struct.unpack_from("<HH", d, 0x36)
    assert e_phentsize == 56
    phdrs = [list(struct.unpack_from("<IIQQQQQQ", d, e_phoff + i * 56)) for i in range(e_phnum)]
    new_phdrs, blobs = [], []  # blobs: (phdr-index-in-new, bytes)
    for ph in phdrs:
        p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = ph
        if p_type != 1 or not (a.base <= p_paddr < a.base + a.size):
            new_phdrs.append(ph); continue
        assert p_paddr % a.unit == 0, f"segment at {p_paddr:#x} not {a.unit}-byte aligned"
        data = bytes(d[p_offset:p_offset + p_filesz]) + b"\0" * (p_memsz - p_filesz)
        data += b"\0" * (-len(data) % a.unit)
        runs = {}  # slice -> [start, bytearray]
        for i in range(0, len(data), a.unit):
            src = p_paddr + i; dst = forward(src, a.base, a.size, a.unit, a.slices)
            c = (dst - a.base) >> (a.size.bit_length() - 1 - (a.slices.bit_length() - 1))
            if c not in runs: runs[c] = [dst, bytearray()]
            else: assert runs[c][0] + len(runs[c][1]) == dst, "hashed run not contiguous"
            runs[c][1] += data[i:i + a.unit]
        for c in sorted(runs):
            start, blob = runs[c]
            new_phdrs.append([1, p_flags, 0, start, start, len(blob), len(blob), a.unit]); blobs.append((len(new_phdrs) - 1, bytes(blob)))
        print(f"GPU segment {p_paddr:#x}+{p_memsz:#x} -> " + ", ".join(f"{runs[c][0]:#x}+{len(runs[c][1]):#x}" for c in sorted(runs)))
    # append blobs, then the new program header table
    for idx, blob in blobs:
        d += b"\0" * (-len(d) % 16); new_phdrs[idx][2] = len(d); d += blob
    d += b"\0" * (-len(d) % 8); new_phoff = len(d)
    for ph in new_phdrs: d += struct.pack("<IIQQQQQQ", *ph)
    struct.pack_into("<Q", d, 0x20, new_phoff); struct.pack_into("<H", d, 0x38, len(new_phdrs))
    open(a.out, "wb").write(d); print(f"wrote {a.out}: {len(new_phdrs)} program headers")
    if a.check:  # rebuild the unhashed image from the new ELF and compare with the original segments
        img = {}
        for ph in new_phdrs:
            if ph[0] != 1 or not (a.base <= ph[4] < a.base + a.size): continue
            blob = d[ph[2]:ph[2] + ph[5]]
            for i in range(0, len(blob), a.unit):
                dst = ph[4] + i
                # invert by brute force over the 4 candidates sharing the same o
                rb = a.size.bit_length() - 1; ub = a.unit.bit_length() - 1; sb = a.slices.bit_length() - 1
                off = dst - a.base; c = off >> (rb - sb); o = (off >> ub) & ((1 << (rb - ub - sb)) - 1)
                k = c ^ fold(o, sb, a.slices); src = a.base + (((o << sb) | k) << ub)
                assert forward(src, a.base, a.size, a.unit, a.slices) == dst
                img[src] = bytes(blob[i:i + a.unit])
        for ph in phdrs:
            if ph[0] != 1 or not (a.base <= ph[4] < a.base + a.size): continue
            data = bytes(open(a.inp, "rb").read()[ph[2]:ph[2] + ph[5]]) + b"\0" * (ph[6] - ph[5]); data += b"\0" * (-len(data) % a.unit)
            for i in range(0, len(data), a.unit): assert img[ph[4] + i] == data[i:i + a.unit], f"mismatch at {ph[4]+i:#x}"
        print("check OK: hashed image inverts back to the original GPU segments")

if __name__ == "__main__": main()
