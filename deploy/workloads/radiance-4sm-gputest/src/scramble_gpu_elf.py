#!/usr/bin/env python3
"""Rewrite a Radiance .soc.elf so that its GPU-memory LOAD segments are already in the hashed DRAM
layout (radiance.memory.AddressHashParams.forward, incl. channelsPerSlice / chanSetStride), for
loaders that write DRAM directly (FireSim LoadMem, +loadmem). Non-GPU segments, section headers and
symbols are kept. Defaults = RadianceHBMConfig (WithRadianceHBM(gpuChannelsPerSlice = 2, gpuSlices = 8)):
2 GiB at 0x1_0000_0000, 32 B units, 16 channels, 2 per L2 slice, chanSetStride 128
(= (256 sets / 2) * 32 B / 32 B). For the 2-SM Radiance2SMHBMConfig use --slices 4 --channels-per-slice 1.
usage: scramble_gpu_elf.py in.elf out.elf [--base 0x100000000] [--size 0x80000000] [--unit 32]
       [--slices 16] [--channels-per-slice 2] [--chan-set-stride 128] [--check]"""
import argparse, struct

class Hash:
    """Port of AddressHashParams' reference model (radiance/memory/AddressHash.scala)."""
    def __init__(self, base, size, unit, nslices, cps, stride):
        assert size & (size - 1) == 0 and base % size == 0, "range must be a power of two aligned to its size"
        assert unit & (unit - 1) == 0 and nslices & (nslices - 1) == 0 and nslices >= 2
        assert cps & (cps - 1) == 0 and cps <= nslices
        self.base, self.size, self.unit, self.n, self.cps, self.stride = base, size, unit, nslices, cps, stride
        self.range_bits = size.bit_length() - 1; self.unit_bits = unit.bit_length() - 1
        self.slice_bits = nslices.bit_length() - 1
        self.unit_index_bits = self.range_bits - self.unit_bits - self.slice_bits
        self.l2_slices = nslices // cps
        assert stride == 0 or (stride & (stride - 1) == 0 and stride * cps <= (1 << self.unit_index_bits))
    def in_range(self, a): return self.base <= a < self.base + self.size
    def fold(self, o):
        acc = 0
        while o: acc ^= o & (self.n - 1); o >>= self.slice_bits
        return acc
    def place(self, c):   return (c % self.l2_slices) * self.cps + c // self.l2_slices
    def unplace(self, p): return (p % self.cps) * self.l2_slices + p // self.cps
    def flip(self, c):    return (c // self.l2_slices) * self.stride
    def forward(self, a):
        if not self.in_range(a): return a
        off = a - self.base; byte = off & (self.unit - 1); v = off >> self.unit_bits; o = v >> self.slice_bits
        c = (v & (self.n - 1)) ^ self.fold(o)
        return self.base + (self.place(c) << (self.range_bits - self.slice_bits)) + ((o ^ self.flip(c)) << self.unit_bits) + byte
    def inverse(self, a):
        if not self.in_range(a): return a
        off = a - self.base; byte = off & (self.unit - 1)
        c = self.unplace(off >> (self.range_bits - self.slice_bits))
        o = ((off >> self.unit_bits) & ((1 << self.unit_index_bits) - 1)) ^ self.flip(c)
        k = c ^ self.fold(o)
        return self.base + (((o << self.slice_bits) | k) << self.unit_bits) + byte
    def channel(self, a): return (a - self.base) >> (self.range_bits - self.slice_bits)

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("inp"); ap.add_argument("out")
    ap.add_argument("--base", type=lambda x: int(x, 0), default=0x1_0000_0000)
    ap.add_argument("--size", type=lambda x: int(x, 0), default=0x8000_0000)
    ap.add_argument("--unit", type=int, default=32); ap.add_argument("--slices", type=int, default=16)
    ap.add_argument("--channels-per-slice", type=int, default=2); ap.add_argument("--chan-set-stride", type=int, default=128)
    ap.add_argument("--check", action="store_true"); a = ap.parse_args()
    h = Hash(a.base, a.size, a.unit, a.slices, a.channels_per_slice, a.chan_set_stride)
    # model self-check (same as the Scala `locally` block): forward is a bijection onto the range
    import random; rnd = random.Random(1)
    for x in [h.base, h.base + h.size - 1, h.base + h.unit, h.base + h.unit * h.n] + [h.base + rnd.getrandbits(h.range_bits) for _ in range(4096)]:
        f = h.forward(x); assert h.in_range(f) and h.inverse(f) == x, f"hash model is not a bijection at {x:#x}"
    d = bytearray(open(a.inp, "rb").read())
    assert d[:4] == b"\x7fELF" and d[4] == 2 and d[5] == 1, "need ELF64 little-endian"
    e_phoff, = struct.unpack_from("<Q", d, 0x20); e_phentsize, e_phnum = struct.unpack_from("<HH", d, 0x36)
    assert e_phentsize == 56
    phdrs = [list(struct.unpack_from("<IIQQQQQQ", d, e_phoff + i * 56)) for i in range(e_phnum)]
    new_phdrs, blobs = [], []  # blobs: (phdr-index-in-new, bytes)
    for ph in phdrs:
        p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = ph
        if p_type != 1 or not h.in_range(p_paddr):
            new_phdrs.append(ph); continue
        assert p_paddr % a.unit == 0, f"segment at {p_paddr:#x} not {a.unit}-byte aligned"
        data = bytes(d[p_offset:p_offset + p_filesz]) + b"\0" * (p_memsz - p_filesz)
        data += b"\0" * (-len(data) % a.unit)
        runs = []  # [start, bytearray], one per contiguous hashed run
        for i in range(0, len(data), a.unit):
            dst = h.forward(p_paddr + i)
            if runs and runs[-1][0] + len(runs[-1][1]) == dst: runs[-1][1] += data[i:i + a.unit]
            else: runs.append([dst, bytearray(data[i:i + a.unit])])
        runs.sort(key=lambda r: r[0])
        for start, blob in runs:
            new_phdrs.append([1, p_flags, 0, start, start, len(blob), len(blob), a.unit]); blobs.append((len(new_phdrs) - 1, bytes(blob)))
        chans = sorted({h.channel(r[0]) for r in runs})
        print(f"GPU segment {p_paddr:#x}+{p_memsz:#x} -> {len(runs)} hashed runs over channels {chans}: " +
              ", ".join(f"{r[0]:#x}+{len(r[1]):#x}" for r in runs[:8]) + (" ..." if len(runs) > 8 else ""))
    for idx, blob in blobs:
        d += b"\0" * (-len(d) % 16); new_phdrs[idx][2] = len(d); d += blob
    d += b"\0" * (-len(d) % 8); new_phoff = len(d)
    for ph in new_phdrs: d += struct.pack("<IIQQQQQQ", *ph)
    struct.pack_into("<Q", d, 0x20, new_phoff); struct.pack_into("<H", d, 0x38, len(new_phdrs))
    open(a.out, "wb").write(d); print(f"wrote {a.out}: {len(new_phdrs)} program headers")
    if a.check:  # rebuild the unhashed image from the new ELF and compare with the original segments
        img = {}
        for ph in new_phdrs:
            if ph[0] != 1 or not h.in_range(ph[4]): continue
            blob = d[ph[2]:ph[2] + ph[5]]
            for i in range(0, len(blob), a.unit):
                src = h.inverse(ph[4] + i); assert h.forward(src) == ph[4] + i; img[src] = bytes(blob[i:i + a.unit])
        orig = open(a.inp, "rb").read()
        for ph in phdrs:
            if ph[0] != 1 or not h.in_range(ph[4]): continue
            data = bytes(orig[ph[2]:ph[2] + ph[5]]) + b"\0" * (ph[6] - ph[5]); data += b"\0" * (-len(data) % a.unit)
            for i in range(0, len(data), a.unit): assert img[ph[4] + i] == data[i:i + a.unit], f"mismatch at {ph[4]+i:#x}"
        print("check OK: hashed image inverts back to the original GPU segments")

if __name__ == "__main__": main()
