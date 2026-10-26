// See LICENSE for license details.
// Real generated DMA RTL; external memory and private stream endpoints modeled.
#include "VSlashDDRStreamDMA.h"
#include "verilated.h"
#include <algorithm>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>

void require(bool condition, const char *message) {
  if (!condition)
    throw std::runtime_error(message);
}
uint64_t pattern(uint64_t index) {
  return 0x9e3779b97f4a7c15ULL * (index + 1) ^ 0xa5b63718f0d249ceULL;
}
int main(int argc, char **argv) {
  try {
    require(argc == 3 || argc == 4, "scenario and maximum burst required");
    const std::string scenario(argv[1]);
    const unsigned maximum = std::stoul(argv[2]);
    const bool invalid = scenario.rfind("invalid-", 0) == 0;
    const bool write = scenario.rfind("write", 0) == 0;
    const bool stalls = scenario.find("stalls") != std::string::npos;
    const bool error = scenario.find("error") != std::string::npos;
    Verilated::commandArgs(argc, argv);
    VSlashDDRStreamDMA dut;
    dut.clock = 0;
    dut.reset = 1;
    dut.io_command_valid = 0;
    dut.io_address = 8128; // Last token before a 4 KiB boundary.
    dut.io_streamAddress = 128;
#ifdef SLASH_REAL_STREAM_ENGINE
    const unsigned channel = std::stoul(argv[3]);
    require(channel < 2, "invalid stream channel");
    const unsigned stream_address = channel * 256;
    dut.io_streamAddress = stream_address;
#endif
    dut.io_bytes = 4224; // Multiple bursts/pages, includes a short final burst.
    dut.io_command_bits = write ? 2 : 1;
    dut.io_mem_ar_ready = dut.io_mem_aw_ready = dut.io_mem_w_ready = 0;
    dut.io_mem_r_valid = dut.io_mem_b_valid = 0;
    dut.io_mem_r_bits_data = 0;
    dut.io_mem_r_bits_resp = dut.io_mem_b_bits_resp = 0;
    dut.io_mem_r_bits_last = 0;
    dut.io_mem_r_bits_id = dut.io_mem_b_bits_id = 0;
#ifndef SLASH_REAL_STREAM_ENGINE
    dut.io_stream_aw_ready = dut.io_stream_w_ready = dut.io_stream_b_valid = 0;
    dut.io_stream_ar_ready = dut.io_stream_r_valid = 0;
    dut.io_stream_r_bits_data = 0;
    dut.io_stream_r_bits_id = dut.io_stream_b_bits_id = 0;
    dut.io_stream_r_bits_resp = dut.io_stream_b_bits_resp = 0;
    dut.io_stream_r_bits_last = 1;
#else
    dut.io_toHost_0_valid = dut.io_toHost_1_valid = 0;
    dut.io_fromHost_0_ready = dut.io_fromHost_1_ready = 0;
    for (unsigned i = 0; i < 16; ++i)
      dut.io_toHost_0_bits[i] = dut.io_toHost_1_bits[i] = 0;
#endif
    for (unsigned i = 0; i < 4; ++i) {
      dut.clock = 0;
      dut.eval();
      dut.clock = 1;
      dut.eval();
    }
    dut.reset = 0;
    if (scenario == "invalid-command")
      dut.io_command_bits = 3;
    if (scenario == "invalid-reserved")
      dut.io_address = 4032;
    if (scenario == "invalid-end")
      dut.io_address = (1u << 20) - 64;
    if (scenario == "invalid-wrap")
      dut.io_address = UINT64_MAX - 63;
    if (scenario == "invalid-token")
      dut.io_bytes = 65;
    if (scenario == "invalid-stream")
      dut.io_streamAddress = 1;
    if (scenario == "invalid-zero")
      dut.io_bytes = 0;
    if (scenario == "invalid-alignment")
      dut.io_address += 1;
    if (scenario == "invalid-capacity")
      dut.io_bytes = (2u << 20) + 64;
    const unsigned repetitions =
        scenario.find("repeat") != std::string::npos ? 3 : 1;
    for (unsigned repetition = 0; repetition < repetitions; ++repetition) {
      bool command_sent = false, completed = false, pending_b = false;
      unsigned accepted = 0, memory_beats = 0, stream_beats = 0;
      unsigned burst_left = 0, burst_total = 0, bursts = 0;
      uint64_t burst_address = 0;
      uint64_t next_address = dut.io_address;
      for (unsigned cycle = 0; cycle < 20000; ++cycle) {
        dut.clock = 0;
        dut.io_command_valid = !command_sent;
        // Deterministic schedules; responses remain stable while stalled.
        dut.io_mem_ar_ready = !stalls || cycle % 7 >= 2;
        dut.io_mem_aw_ready = !stalls || cycle % 11 >= 3;
        dut.io_mem_w_ready = !stalls || cycle % 13 >= 4;
        dut.io_mem_r_valid = !write && burst_left;
        dut.io_mem_r_bits_data =
            pattern((burst_address + (burst_total - burst_left) * 8) / 8);
        dut.io_mem_r_bits_last =
            scenario == "read-last-error" ? 0 : burst_left == 1;
        dut.io_mem_r_bits_resp =
            scenario == "read-error" && memory_beats == 2 ? 2 : 0;
        dut.io_mem_b_valid = pending_b;
        dut.io_mem_b_bits_resp = scenario == "write-error" ? 2 : 0;
        const bool stream_ready = !stalls || cycle % 31 >= 23;
#ifndef SLASH_REAL_STREAM_ENGINE
        dut.io_stream_aw_ready = dut.io_stream_w_ready = dut.io_stream_b_valid =
            0;
        dut.io_stream_ar_ready = dut.io_stream_r_valid = 0;
        dut.io_stream_r_bits_data = pattern(stream_beats);
        dut.eval();
        // Match CPUManagedStreamEngine's private single-beat atomic handshake.
        dut.io_stream_aw_ready =
            stream_ready && dut.io_stream_w_valid && dut.io_stream_b_ready;
        dut.io_stream_w_ready =
            stream_ready && dut.io_stream_aw_valid && dut.io_stream_b_ready;
        dut.io_stream_b_valid =
            stream_ready && dut.io_stream_aw_valid && dut.io_stream_w_valid;
        dut.io_stream_ar_ready = stream_ready && dut.io_stream_r_ready;
        dut.io_stream_r_valid = stream_ready && dut.io_stream_ar_valid;
#else
        dut.io_fromHost_0_ready = channel == 0 && stream_ready;
        dut.io_fromHost_1_ready = channel == 1 && stream_ready;
        dut.io_toHost_0_valid =
            write && channel == 0 && stream_beats < dut.io_bytes / 8;
        dut.io_toHost_1_valid =
            write && channel == 1 && stream_beats < dut.io_bytes / 8;
        for (unsigned beat = 0; beat < 8; ++beat) {
          const uint64_t value = pattern(stream_beats + beat);
          dut.io_toHost_0_bits[beat * 2] = dut.io_toHost_1_bits[beat * 2] =
              uint32_t(value);
          dut.io_toHost_0_bits[beat * 2 + 1] =
              dut.io_toHost_1_bits[beat * 2 + 1] = uint32_t(value >> 32);
        }
#endif
        dut.eval();
        if (dut.io_command_valid && dut.io_command_ready) {
          command_sent = true;
          ++accepted;
        }
        const bool ar = dut.io_mem_ar_valid && dut.io_mem_ar_ready;
        const bool aw = dut.io_mem_aw_valid && dut.io_mem_aw_ready;
        if (ar || aw) {
          require(!invalid, "invalid command issued a memory transaction");
          require(!burst_left && !pending_b, "overlapping memory bursts");
          const auto address =
              ar ? dut.io_mem_ar_bits_addr : dut.io_mem_aw_bits_addr;
          const auto len = ar ? dut.io_mem_ar_bits_len : dut.io_mem_aw_bits_len;
          require(address == next_address, "memory address lost or duplicated");
          require((ar ? dut.io_mem_ar_bits_size : dut.io_mem_aw_bits_size) == 3,
                  "memory beat size");
          require((ar ? dut.io_mem_ar_bits_burst : dut.io_mem_aw_bits_burst) ==
                      1,
                  "memory burst type");
          burst_left = burst_total = len + 1;
          require(burst_total * 8 <= maximum &&
                      (address & 4095) + burst_total * 8 <= 4096,
                  "burst exceeded maximum or crossed 4 KiB");
          burst_address = address;
          next_address += burst_total * 8;
          ++bursts;
        }
        if (dut.io_mem_r_valid && dut.io_mem_r_ready) {
          --burst_left;
          ++memory_beats;
        }
        if (dut.io_mem_w_valid && dut.io_mem_w_ready) {
          require(burst_left && write, "unexpected memory write");
          require(dut.io_mem_w_bits_data == pattern(memory_beats),
                  "memory payload order/data");
          require(dut.io_mem_w_bits_strb == 255 &&
                      dut.io_mem_w_bits_last == (burst_left == 1),
                  "memory write mask/last");
          --burst_left;
          ++memory_beats;
          if (!burst_left)
            pending_b = true;
        }
        if (dut.io_mem_b_valid && dut.io_mem_b_ready)
          pending_b = false;
#ifndef SLASH_REAL_STREAM_ENGINE
        if (dut.io_stream_w_valid && dut.io_stream_w_ready) {
          require(!write && !invalid, "unexpected stream write");
          // An AXI error beat is discarded by this implementation.
          const unsigned skipped =
              scenario == "read-error" && stream_beats >= 2 ? 1 : 0;
          require(dut.io_stream_w_bits_data ==
                      pattern(dut.io_address / 8 + stream_beats + skipped),
                  "stream payload order/data");
          require(dut.io_stream_aw_bits_addr == 128 &&
                      dut.io_stream_aw_bits_len == 0 &&
                      dut.io_stream_w_bits_last &&
                      dut.io_stream_w_bits_strb == 255,
                  "stream write metadata");
          ++stream_beats;
        }
#else
        require(!(channel ? dut.io_fromHost_0_valid : dut.io_fromHost_1_valid),
                "token routed to wrong stream channel");
        if ((channel ? dut.io_toHost_1_valid && dut.io_toHost_1_ready
                     : dut.io_toHost_0_valid && dut.io_toHost_0_ready))
          stream_beats += 8;
        if ((channel ? dut.io_fromHost_1_valid && dut.io_fromHost_1_ready
                     : dut.io_fromHost_0_valid && dut.io_fromHost_0_ready)) {
          require(!write, "unexpected from-host token");
          const auto &token =
              channel ? dut.io_fromHost_1_bits : dut.io_fromHost_0_bits;
          for (unsigned beat = 0; beat < 8; ++beat) {
            const uint64_t actual = uint64_t(token[beat * 2]) |
                                    (uint64_t(token[beat * 2 + 1]) << 32);
            require(actual == pattern(dut.io_address / 8 + stream_beats + beat),
                    "real stream engine token packing/data order");
          }
          stream_beats += 8;
        }
#endif
#ifndef SLASH_REAL_STREAM_ENGINE
        if (dut.io_stream_r_valid && dut.io_stream_r_ready) {
          require(write && !invalid && dut.io_stream_ar_bits_addr == 128 &&
                      dut.io_stream_ar_bits_len == 0,
                  "stream read metadata");
          ++stream_beats;
        }
#endif
        const auto status = dut.io_status;
        dut.clock = 1;
        dut.eval();
        if (command_sent && !dut.io_command_valid && (status & 2)) {
#ifdef SLASH_REAL_STREAM_ENGINE
          if (stream_beats < dut.io_bytes / 8)
            continue; // DMA completion can precede draining the bridge FIFO.
#endif
          require(accepted == 1, "command accepted repeatedly");
          require(status == ((invalid || error) ? 6u : 2u),
                  "completion/error status");
          require(!burst_left && !pending_b,
                  "completion before memory responses drained");
          if (!invalid && !error)
            require(memory_beats == dut.io_bytes / 8 &&
                        stream_beats == memory_beats,
                    "incomplete transfer or duplicated beats");
          if (invalid)
            require(!bursts && !stream_beats,
                    "invalid command had stream effects");
          completed = true;
          std::cout << scenario << " burst=" << maximum
                    << " repetition=" << repetition << " cycles=" << cycle
                    << " bursts=" << bursts << " mem=" << memory_beats
                    << " stream=" << stream_beats << " status=" << status
                    << '\n';
          break;
        }
      }
      require(completed, "DMA did not complete within 20000 cycles");
    }
    dut.final();
    return 0;
  } catch (const std::exception &error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
