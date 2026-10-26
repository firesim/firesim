// See LICENSE for license details.

#include "VBankConflictRegressionHarness.h"
#include "verilated.h"

#include <array>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
int free_id(const std::array<bool, 4> &pending) {
  for (unsigned i = 0; i < pending.size(); ++i)
    if (!pending[i])
      return i;
  return -1;
}
} // namespace

int main(int argc, char **argv) {
  try {
    if (argc != 2)
      throw std::runtime_error("expected one traffic scenario");
    const std::string scenario = argv[1];
    const bool stalls = scenario == "response-stalls";
    if (!stalls && scenario != "read-only" && scenario != "write-only" &&
        scenario != "simultaneous")
      throw std::runtime_error("unknown traffic scenario");
    const unsigned goal_reads = scenario == "write-only" ? 0 : 32;
    const unsigned goal_writes = scenario == "read-only" ? 0 : 32;
    Verilated::commandArgs(argc, argv);
    VBankConflictRegressionHarness dut;
    dut.clock = 0;
    dut.reset = 1;
    dut.io_readValid = dut.io_writeValid = 0;
    dut.io_readId = dut.io_writeId = 0;
    dut.io_responseReady = 0;
    for (unsigned i = 0; i < 4; ++i) {
      dut.clock = 0;
      dut.eval();
      dut.clock = 1;
      dut.eval();
    }
    dut.reset = 0;
    std::array<bool, 4> pending_reads{}, pending_writes{};
    unsigned sent_reads = 0, sent_writes = 0;
    unsigned got_reads = 0, got_writes = 0, simultaneous = 0;
    for (unsigned cycle = 0; cycle < 5000; ++cycle) {
      const int read_id = free_id(pending_reads);
      const int write_id = free_id(pending_writes);
      dut.clock = 0;
      dut.io_readValid = read_id >= 0 && sent_reads < goal_reads;
      dut.io_writeValid = write_id >= 0 && sent_writes < goal_writes;
      dut.io_readId = read_id >= 0 ? read_id : 0;
      dut.io_writeId = write_id >= 0 ? write_id : 0;
      dut.io_responseReady = !stalls || cycle % 11 >= 7;
      dut.eval();
      const bool accept_read = dut.io_readValid && dut.io_readReady;
      const bool accept_write = dut.io_writeValid && dut.io_writeReady;
      simultaneous += accept_read && accept_write;
      if (accept_read) {
        pending_reads[read_id] = true;
        ++sent_reads;
      }
      if (accept_write) {
        pending_writes[write_id] = true;
        ++sent_writes;
      }
      if (dut.io_readResponseValid && dut.io_responseReady) {
        const unsigned id = dut.io_readResponseId;
        if (id >= pending_reads.size() || !pending_reads[id])
          throw std::runtime_error("duplicate or unexpected read response");
        pending_reads[id] = false;
        ++got_reads;
      }
      if (dut.io_writeResponseValid && dut.io_responseReady) {
        const unsigned id = dut.io_writeResponseId;
        if (id >= pending_writes.size() || !pending_writes[id])
          throw std::runtime_error("duplicate or unexpected write response");
        pending_writes[id] = false;
        ++got_writes;
      }
      dut.clock = 1;
      dut.eval();
      if (got_reads == goal_reads && got_writes == goal_writes) {
        if (goal_reads && goal_writes && simultaneous == 0)
          throw std::runtime_error("test did not accept simultaneous requests");
        std::cout << scenario
                  << ": PASS; accepted/completed reads=" << sent_reads << "/"
                  << got_reads << ", writes=" << sent_writes << "/"
                  << got_writes << ", simultaneous=" << simultaneous
                  << ", cycles=" << cycle + 1 << std::endl;
        dut.final();
        return 0;
      }
    }
    std::cerr << scenario
              << ": incomplete after 5000 cycles; reads=" << got_reads << "/"
              << sent_reads << ", writes=" << got_writes << "/" << sent_writes
              << ", simultaneous=" << simultaneous << std::endl;
    dut.final();
    return 1;
  } catch (const std::exception &error) {
    std::cerr << error.what() << std::endl;
    return 1;
  }
}
