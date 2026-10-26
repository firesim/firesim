// See LICENSE for license details.
// PREDICTED DDR memory, deterministic bounded AXI/MMIO stimulus; no board I/O.
#include "VDDRManagedStreamHarness.h"
#include "registers.h"
#include "verilated.h"
#include <array>
#include <cstdio>
#include <deque>
#include <fstream>
#include <map>
#include <stdexcept>
#include <string>

static void check(bool v, const char *m) {
  if (!v)
    throw std::runtime_error(m);
}
static uint64_t pattern(unsigned token, unsigned word) {
  return 0x1234567800000000ULL ^ (uint64_t(token) << 20) ^
         (word * 0x100010001ULL);
}
struct Burst {
  uint64_t addr;
  unsigned left, total, id;
};
class Test {
public:
  VDDRManagedStreamHarness dut;
  unsigned cycles = 0, produced = 0, received = 0, written = 0, read_bytes = 0,
           write_bytes = 0;
  unsigned target = 0, next_write_token = 0, next_write_word = 0,
           read_token = 0;
  bool sink_ready = true, stalls = false, offered_read = false;
  bool held_aw = false, held_ar = false;
  uint64_t aw_address = 0, ar_address = 0;
  unsigned aw_length = 0, ar_length = 0;
  std::string error;
  bool aw = false, w = false, b = false, ar = false, r = false;
  uint32_t rdata = 0;
  std::deque<Burst> reads, writes;
  std::deque<std::pair<unsigned, unsigned>> responses;
  std::map<uint64_t, uint64_t> memory;
  std::ofstream csv;
  explicit Test(const std::string &name) : csv(name + ".csv") {
    csv << "cycle,produced,received,read_bytes,write_bytes,read_inflight,write_"
           "inflight\n";
    dut.io_ctrl_aw_bits_size = dut.io_ctrl_ar_bits_size = 2;
    dut.io_ctrl_aw_bits_burst = dut.io_ctrl_ar_bits_burst = 1;
    dut.io_ctrl_w_bits_strb = 15;
    dut.io_ctrl_w_bits_last = 1;
    dut.io_ctrl_b_ready = dut.io_ctrl_r_ready = 0;
    dut.reset = 1;
    for (unsigned i = 0; i < 5; ++i)
      tick();
    dut.reset = 0;
    tick();
  }
  void tick() {
    check(++cycles < 100000, "cycle budget exceeded");
    dut.clock = 0;
    dut.io_toHost_0_valid = produced < target;
    for (unsigned i = 0; i < 8; ++i) {
      const auto v = pattern(produced, i);
      dut.io_toHost_0_bits[2 * i] = v;
      dut.io_toHost_0_bits[2 * i + 1] = v >> 32;
    }
    dut.io_fromHost_0_ready = sink_ready && (!stalls || cycles % 5 != 1);
    dut.io_mem_ar_ready = !stalls || cycles % 7 >= 3;
    dut.io_mem_aw_ready = !stalls || cycles % 11 >= 5;
    dut.io_mem_w_ready = !writes.empty() && (!stalls || cycles % 3 != 1);
    const bool have_b =
        !responses.empty() && cycles >= responses.front().second;
    dut.io_mem_b_valid = have_b;
    dut.io_mem_b_bits_id = have_b ? responses.front().first : 0;
    dut.io_mem_b_bits_resp = error == "write-error" ? 2 : 0;
    offered_read |= !reads.empty() && (!stalls || cycles % 4 != 2);
    dut.io_mem_r_valid = offered_read;
    dut.io_mem_r_bits_resp = error == "read-error" ? 2 : 0;
    dut.io_mem_r_bits_id = reads.empty() ? 0 : reads.front().id;
    dut.io_mem_r_bits_last = !reads.empty() && (error == "read-last-error"
                                                    ? reads.front().left != 1
                                                    : reads.front().left == 1);
    const uint64_t ra = reads.empty() ? 0 : reads.front().addr;
#if AXI_BITS == 64
    dut.io_mem_r_bits_data = memory[ra];
#else
    for (unsigned i = 0; i < 8; ++i) {
      const auto v = memory[ra + 8 * i];
      dut.io_mem_r_bits_data[2 * i] = v;
      dut.io_mem_r_bits_data[2 * i + 1] = v >> 32;
    }
#endif
    dut.eval();
    if (held_aw)
      check(dut.io_mem_aw_valid && dut.io_mem_aw_bits_addr == aw_address &&
                dut.io_mem_aw_bits_len == aw_length,
            "AW changed under backpressure");
    if (held_ar)
      check(dut.io_mem_ar_valid && dut.io_mem_ar_bits_addr == ar_address &&
                dut.io_mem_ar_bits_len == ar_length,
            "AR changed under backpressure");
    held_aw = dut.io_mem_aw_valid && !dut.io_mem_aw_ready;
    held_ar = dut.io_mem_ar_valid && !dut.io_mem_ar_ready;
    aw_address = dut.io_mem_aw_bits_addr;
    aw_length = dut.io_mem_aw_bits_len;
    ar_address = dut.io_mem_ar_bits_addr;
    ar_length = dut.io_mem_ar_bits_len;
    aw = dut.io_ctrl_aw_valid && dut.io_ctrl_aw_ready;
    w = dut.io_ctrl_w_valid && dut.io_ctrl_w_ready;
    b = dut.io_ctrl_b_valid && dut.io_ctrl_b_ready;
    ar = dut.io_ctrl_ar_valid && dut.io_ctrl_ar_ready;
    r = dut.io_ctrl_r_valid && dut.io_ctrl_r_ready;
    rdata = dut.io_ctrl_r_bits_data;
    if (!dut.reset) {
      if (dut.io_toHost_0_valid && dut.io_toHost_0_ready)
        ++produced;
      if (dut.io_fromHost_0_valid && dut.io_fromHost_0_ready) {
        for (unsigned i = 0; i < 8; ++i) {
          auto &v = dut.io_fromHost_0_bits;
          check((uint64_t(v[2 * i]) | (uint64_t(v[2 * i + 1]) << 32)) ==
                    pattern(received, i),
                "read token payload/order");
        }
        ++received;
      }
      // Responses are retired before new addresses can enter the software
      // queues.
      if (dut.io_mem_b_valid && dut.io_mem_b_ready)
        responses.pop_front();
      if (dut.io_mem_r_valid && dut.io_mem_r_ready) {
        offered_read = false;
        auto &v = reads.front();
        v.addr += AXI_BITS / 8;
        read_bytes += AXI_BITS / 8;
        if (!--v.left)
          reads.pop_front();
      }
      if (dut.io_mem_w_valid && dut.io_mem_w_ready) {
        check(!writes.empty(), "write data without address");
        auto &v = writes.front();
        check(dut.io_mem_w_bits_last == (v.left == 1), "write last differs");
#if AXI_BITS == 64
        check(dut.io_mem_w_bits_strb == 255, "write strobe");
        check(dut.io_mem_w_bits_data ==
                  pattern(next_write_token, next_write_word),
              "write payload/order");
        memory[v.addr] = dut.io_mem_w_bits_data;
        if (++next_write_word == 8) {
          next_write_word = 0;
          ++next_write_token;
        }
#else
        check(dut.io_mem_w_bits_strb == ~uint64_t(0), "write strobe");
        for (unsigned i = 0; i < 8; ++i) {
          auto &d = dut.io_mem_w_bits_data;
          const auto value =
              uint64_t(d[2 * i]) | (uint64_t(d[2 * i + 1]) << 32);
          check(value == pattern(next_write_token, i),
                "wide write payload/order");
          memory[v.addr + 8 * i] = value;
        }
        ++next_write_token;
#endif
        v.addr += AXI_BITS / 8;
        write_bytes += AXI_BITS / 8;
        if (!--v.left) {
          responses.emplace_back(v.id, cycles + (stalls ? 29 : 3));
          writes.pop_front();
        }
      }
      if (dut.io_mem_aw_valid && dut.io_mem_aw_ready) {
        const uint64_t addr = dut.io_mem_aw_bits_addr;
        const unsigned count = dut.io_mem_aw_bits_len + 1;
        check(addr == 0x10000 + (written % 8192), "write ring pointer");
        check((addr % 4096) + count * (AXI_BITS / 8) <= 4096 && count <= 256,
              "write burst page/length");
        check(dut.io_mem_aw_bits_size == (AXI_BITS == 64 ? 3 : 6),
              "write size");
        writes.push_back({addr, count, count, dut.io_mem_aw_bits_id});
        written += count * (AXI_BITS / 8);
      }
      if (dut.io_mem_ar_valid && dut.io_mem_ar_ready) {
        const uint64_t addr = dut.io_mem_ar_bits_addr;
        const unsigned count = dut.io_mem_ar_bits_len + 1;
        check(addr == 0x20000 + (read_token % 8192), "read ring pointer");
        check((addr % 4096) + count * (AXI_BITS / 8) <= 4096 && count <= 256,
              "read burst page/length");
        check(dut.io_mem_ar_bits_size == (AXI_BITS == 64 ? 3 : 6), "read size");
        reads.push_back({addr, count, count, dut.io_mem_ar_bits_id});
        read_token += count * (AXI_BITS / 8);
      }
    }
    csv << cycles << ',' << produced << ',' << received << ',' << read_bytes
        << ',' << write_bytes << ',' << reads.size() << ',' << writes.size()
        << '\n';
    dut.clock = 1;
    dut.eval();
  }
  void write(unsigned address, uint32_t data) {
    dut.io_ctrl_aw_bits_addr = address;
    dut.io_ctrl_w_bits_data = data;
    bool a = false, d = false, done = false;
    for (unsigned n = 0; n < 1000 && !done; ++n) {
      dut.io_ctrl_aw_valid = !a;
      dut.io_ctrl_w_valid = !d && n >= 2;
      dut.io_ctrl_b_ready = 1;
      tick();
      a |= aw;
      d |= w;
      done = b;
    }
    check(a && d && done, "MMIO write timeout");
    dut.io_ctrl_aw_valid = dut.io_ctrl_w_valid = dut.io_ctrl_b_ready = 0;
    tick();
  }
  uint32_t read(unsigned address) {
    dut.io_ctrl_ar_bits_addr = address;
    bool a = false, done = false;
    uint32_t value = 0;
    for (unsigned n = 0; n < 1000 && !done; ++n) {
      dut.io_ctrl_ar_valid = !a;
      dut.io_ctrl_r_ready = n >= 5;
      tick();
      a |= ar;
      if (r) {
        value = rdata;
        done = true;
      }
    }
    check(a && done, "MMIO read timeout");
    dut.io_ctrl_ar_valid = dut.io_ctrl_r_ready = 0;
    tick();
    return value;
  }
  void init() {
    write(source0_toHostPhysAddrHigh, 0);
    write(source0_toHostPhysAddrLow, 0x10000);
    write(source0_toHostStreamDoneInit, 1);
    write(sink0_fromHostPhysAddrHigh, 0);
    write(sink0_fromHostPhysAddrLow, 0x20000);
    write(sink0_fromHostStreamDoneInit, 1);
  }
  unsigned offered = 0;
  void offer(unsigned tokens) {
    for (unsigned t = 0; t < tokens; ++t)
      for (unsigned i = 0; i < 8; ++i)
        memory[0x20000 + ((offered + t) % 128) * 64 + i * 8] =
            pattern(offered + t, i);
    offered += tokens;
    write(sink0_bytesAvailable, tokens * 64);
  }
};
int main(int argc, char **argv) {
  try {
    check(argc == 2, "scenario required");
    const std::string name = argv[1];
    Test t(name);
    t.error = name;
    t.stalls = name.find("stalls") != std::string::npos || name == "duplex";
    const bool writing = name.find("write") == 0 || name == "duplex";
    const bool reading = name.find("read") == 0 || name == "duplex";
    t.target = writing ? (name == "write-one"    ? 1
                          : name == "write-tail" ? 65
                                                 : 193)
                       : 0;
    for (unsigned i = 0; i < 20; ++i)
      t.tick();
    check(t.written == 0, "DMA began before ring initialization");
    t.init();
    if (reading) {
      t.sink_ready = name != "read-reservation";
      t.offer(name == "read-one" ? 1 : 128);
    }
    if (name == "read-reservation") {
      for (unsigned i = 0; i < 3000; ++i)
        t.tick();
      check(t.read_bytes == 16 * 64 && t.read_token == 16 * 64 &&
                t.reads.empty(),
            "reserved burst could not drain independently of bridge");
      check(t.read(sink0_bytesConsumed) == 16 * 64,
            "ring storage not released after local copy");
      t.write(sink0_fromHostStreamFlush, 1);
      check(t.read(sink0_fromHostStreamFlushDone) == 0,
            "flush ignored buffered bridge tokens");
      t.sink_ready = true;
    }
    unsigned reclaimed = 0;
    bool extra = false;
    const unsigned expected = reading ? (name == "read-one" ? 1 : 192) : 0;
    for (unsigned i = 0; i < 40000; ++i) {
      t.tick();
      if (writing && i % 127 == 0) {
        const auto bytes = t.read(source0_bytesAvailable);
        if (bytes) {
          t.write(source0_bytesConsumed, bytes);
          reclaimed += bytes;
        }
      }
      if (reading && expected > 128 && !extra && t.received >= 128) {
        t.offer(64);
        extra = true;
      }
      if (t.received == expected && t.write_bytes == t.target * 64 &&
          t.writes.empty() && t.responses.empty())
        break;
    }
    check(t.received == expected && t.write_bytes == t.target * 64,
          "incomplete stream transfer");
    if (writing) {
      t.write(source0_toHostStreamFlush, 1);
      check(t.read(source0_toHostStreamFlushDone) == 1,
            "write flush did not finish");
      reclaimed += t.read(source0_bytesAvailable);
      check(reclaimed == t.target * 64, "write credits lost or duplicated");
    }
    if (reading) {
      t.write(sink0_fromHostStreamFlush, 1);
      check(t.read(sink0_fromHostStreamFlushDone) == 1,
            "read flush did not finish");
      check(t.read(sink0_bytesConsumed) == expected * 64,
            "read copy count differs");
    }
    std::printf(
        "PASS PREDICTED %s AXI=%d tokens_out=%u tokens_in=%u cycles=%u\n",
        argv[1],
        AXI_BITS,
        t.produced,
        t.received,
        t.cycles);
    return 0;
  } catch (const std::exception &e) {
    std::fprintf(stderr, "%s\n", e.what());
    return 1;
  }
}
