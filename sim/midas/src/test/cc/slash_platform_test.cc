// See LICENSE for license details.
// Full generated platform, production DDR helper and predicted external DDR.
#include "VSlashPlatform.h"
#ifndef SLASH_FPGA_STREAM_TEST
#include "core/v80_slash_ddr_stream.h"
#endif
#include "verilated.h"
#include <fstream>
#include <iostream>
#include <map>
#include <stdexcept>
#include <string>

void require(bool condition, const char *message) {
  if (!condition)
    throw std::runtime_error(message);
}
constexpr uint64_t memory_base = 0x60000000000ULL;
static bool test_control_address(size_t address) {
  if (address % 4)
    return false;
  if (address >= 64 && address < 4096)
    return true;
#ifdef SLASH_TEST_AXIS_DEBUG
  return (address >= 0x100000 && address < 0x101000) ||
         (address >= 0x200000 && address < 0x400000);
#else
  return false;
#endif
}

struct Simulation {
  VSlashPlatform dut;
  std::map<uint64_t, uint64_t> predicted;
  std::ofstream curve{"bus-cycles.csv"};
  unsigned cycles = 0, reads = 0, writes = 0;
  uint64_t read_address = 0, write_address = 0;
  unsigned read_left = 0, write_left = 0, read_id = 0, write_id = 0;
  bool response = false, stalls;
  bool ctrl_aw, ctrl_w, ctrl_b, ctrl_ar, ctrl_r;
  bool held_read = false;
  uint32_t held_data = 0;
  uint32_t ctrl_data;
  explicit Simulation(bool stalls) : stalls(stalls) {
    curve << "cycle,ar,aw,r,w,b,ctrl_ar,ctrl_r,ctrl_aw,ctrl_w,ctrl_b\n";
    dut.ap_clk = 0;
    dut.ap_rst_n = 0;
    dut.s_axi_lite_awaddr = dut.s_axi_lite_araddr = 0;
    dut.s_axi_lite_awprot = dut.s_axi_lite_arprot = 0;
    dut.s_axi_lite_awvalid = dut.s_axi_lite_wvalid = dut.s_axi_lite_arvalid = 0;
    dut.s_axi_lite_wstrb = 15;
    dut.s_axi_lite_wdata = 0;
    dut.s_axi_lite_bready = dut.s_axi_lite_rready = 1;
    for (unsigned i = 0; i < 8; ++i)
      cycle();
    dut.ap_rst_n = 1;
    for (unsigned i = 0; i < 80; ++i)
      cycle();
  }
  void cycle() {
    require(++cycles <= 200000, "full platform exceeded 200000 host cycles");
    dut.ap_clk = 0;
    dut.s_axi_lite_rready = !stalls || cycles % 7 > 3;
    dut.s_axi_lite_bready = !stalls || cycles % 11 > 5;
    dut.host_mem_0_arready = !read_left && (!stalls || cycles % 11 > 3);
    dut.host_mem_0_awready =
        !write_left && !response && (!stalls || cycles % 7 > 2);
    dut.host_mem_0_wready = write_left && (!stalls || cycles % 13 > 6);
    dut.host_mem_0_rvalid = read_left != 0;
    dut.host_mem_0_rdata = predicted[read_address];
    dut.host_mem_0_rid = read_id;
    dut.host_mem_0_rresp = 0;
    dut.host_mem_0_rlast = read_left == 1;
    dut.host_mem_0_bvalid = response;
    dut.host_mem_0_bid = write_id;
    dut.host_mem_0_bresp = 0;
    dut.eval();
    const bool ar = dut.host_mem_0_arvalid && dut.host_mem_0_arready;
    const bool aw = dut.host_mem_0_awvalid && dut.host_mem_0_awready;
    const bool r = dut.host_mem_0_rvalid && dut.host_mem_0_rready;
    const bool w = dut.host_mem_0_wvalid && dut.host_mem_0_wready;
    const bool b = dut.host_mem_0_bvalid && dut.host_mem_0_bready;
    ctrl_aw = dut.s_axi_lite_awvalid && dut.s_axi_lite_awready;
    ctrl_w = dut.s_axi_lite_wvalid && dut.s_axi_lite_wready;
    ctrl_b = dut.s_axi_lite_bvalid && dut.s_axi_lite_bready;
    ctrl_ar = dut.s_axi_lite_arvalid && dut.s_axi_lite_arready;
    ctrl_r = dut.s_axi_lite_rvalid && dut.s_axi_lite_rready;
    ctrl_data = dut.s_axi_lite_rdata;
    if (held_read)
      require(dut.s_axi_lite_rvalid && ctrl_data == held_data,
              "control response changed under backpressure");
    held_read = dut.s_axi_lite_rvalid && !dut.s_axi_lite_rready;
    held_data = ctrl_data;

    require(!ctrl_b || !dut.s_axi_lite_bresp, "control write error");
    require(!ctrl_r || !dut.s_axi_lite_rresp, "control read error");
    curve << cycles << ',' << ar << ',' << aw << ',' << r << ',' << w << ','
          << b << ',' << ctrl_ar << ',' << ctrl_r << ',' << ctrl_aw << ','
          << ctrl_w << ',' << ctrl_b << '\n';
    if (r) {
      --read_left;
      read_address += 8;
      ++reads;
    }
    if (b)
      response = false;
    if (w) {
      require(write_left && dut.host_mem_0_wstrb == 255 &&
                  dut.host_mem_0_wlast == (write_left == 1),
              "memory write last/mask");
      predicted[write_address] = dut.host_mem_0_wdata;
      write_address += 8;
      --write_left;
      ++writes;
      if (!write_left)
        response = true;
    }
    if (ar) {
      read_address = dut.host_mem_0_araddr;
      read_left = dut.host_mem_0_arlen + 1;
      read_id = dut.host_mem_0_arid;
      require(read_address >= memory_base &&
                  read_address < memory_base + (8ULL << 30),
              "untranslated read address");
      require((read_address & 4095) + read_left * 8 <= 4096 &&
                  read_left * 8 <= 256,
              "read burst boundary");
    }
    if (aw) {
      write_address = dut.host_mem_0_awaddr;
      write_left = dut.host_mem_0_awlen + 1;
      write_id = dut.host_mem_0_awid;
      require(write_address >= memory_base &&
                  write_address < memory_base + (8ULL << 30),
              "untranslated write address");
      require((write_address & 4095) + write_left * 8 <= 4096 &&
                  write_left * 8 <= 256,
              "write burst boundary");
    }
    dut.ap_clk = 1;
    dut.eval();
  }
  void write(size_t address, uint32_t value) {
    require(test_control_address(address), "unsafe logical control address");
    dut.s_axi_lite_awaddr = address;
    dut.s_axi_lite_wdata = value;
    bool address_done = false, data_done = false;
    for (unsigned i = 0; i < 1000; ++i) {
      // Exercise independent address/data arrival at the AXI-Lite interface.
      dut.s_axi_lite_awvalid = !address_done;
      dut.s_axi_lite_wvalid = !data_done && (!stalls || i >= 3);
      cycle();
      address_done |= ctrl_aw;
      data_done |= ctrl_w;
      if (ctrl_b) {
        require(address_done && data_done, "write response before AW/W");
        dut.s_axi_lite_awvalid = dut.s_axi_lite_wvalid = 0;
        return;
      }
    }
    throw std::runtime_error("control write timeout");
  }
  uint32_t read(size_t address) {
    require(test_control_address(address), "unsafe logical control address");
    dut.s_axi_lite_araddr = address;
    bool accepted = false;
    for (unsigned i = 0; i < 1000; ++i) {
      dut.s_axi_lite_arvalid = !accepted;
      cycle();
      accepted |= ctrl_ar;
      if (ctrl_r) {
        require(accepted, "read response before AR");
        dut.s_axi_lite_arvalid = 0;
        return ctrl_data;
      }
    }
    throw std::runtime_error("control read timeout");
  }
};
#ifndef SLASH_FPGA_STREAM_TEST
int main(int argc, char **argv) {
  try {
    require(argc == 2, "one scenario required");
    Verilated::commandArgs(argc, argv);
    const std::string name(argv[1]);
    bool known =
        name == "roundtrip" || name == "roundtrip-stalls" || name == "invalid";
#ifdef SLASH_TEST_AXIS_DEBUG
    known |= name == "debug-routing" || name == "debug-routing-stalls";
#endif
    require(known, "unknown scenario");
    Simulation sim(name.find("stalls") != std::string::npos);
    const uint64_t source = V80_DDR_STREAM_MIN_DDR + 4032;
    const uint64_t destination = source + 8192;

#ifdef SLASH_TEST_AXIS_DEBUG
    if (name.find("debug-routing") == 0) {
      const unsigned audit = 0x100000, hub = 0x200000;
      require(sim.read(audit) == 0x56384158, "debug audit identification");
      sim.write(audit, 0xdeadbeef);
      sim.write(hub, 0xa5a5a5a5);
      for (unsigned i = 0; i < 70; ++i) {
        const unsigned offset = (i % 64) * 4;
        require(sim.read(hub + offset) == (0x534c4442 ^ offset),
                "debug hub routing/payload");
      }
      require(sim.read(audit + 8) == 70 && sim.read(audit + 12) == 70 &&
                  sim.read(audit + 16) == 70,
              "debug host/hub/response counts");
      require(sim.read(audit + 20) == 0 && sim.read(audit + 36) == 64,
              "audit error/saturation count");
      require(sim.read(audit + 24) == 20 &&
                  sim.read(audit + 28) == (0x534c4442 ^ 20) &&
                  sim.read(audit + 32) == 4,
              "audit last transaction");
      for (unsigned repetition = 0; repetition < 2; ++repetition) {
        for (unsigned i = 0; i < 64; ++i) {
          require(sim.read(audit + 0x100 + i * 8) == (0x534c4442 ^ (i * 4)),
                  "audit payload trace");
          require(sim.read(audit + 0x104 + i * 8) == (0x80000000U | i * 4),
                  "audit metadata trace");
        }
      }
      require(sim.read(audit + 36) == 64 && sim.read(audit + 12) == 70,
              "audit reads consumed state");
      require(sim.read(audit + 40) == 0 && sim.read(audit + 44) == 0 &&
                  sim.read(audit + 48) == 0,
              "metasimulation debug AXIS tieoffs");
      require(!sim.reads && !sim.writes, "debug access touched target DDR");
    } else
#endif
        if (name == "invalid") {
      sim.write(V80_DDR_STREAM_DDR_LO, source);
      sim.write(V80_DDR_STREAM_DDR_HI, 0);
      sim.write(V80_DDR_STREAM_STREAM, 1);
      sim.write(V80_DDR_STREAM_BYTES, 64);
      sim.write(V80_DDR_STREAM_COMMAND, 1);
      require(sim.read(V80_DDR_STREAM_STATUS) == 6,
              "invalid command not rejected by RTL");
      require(!sim.reads && !sim.writes,
              "invalid command touched external DDR");
    } else {
      for (unsigned repetition = 0; repetition < 3; ++repetition) {
        for (unsigned i = 0; i < 32; ++i)
          sim.predicted[memory_base + source + i * 8] =
              0x123456789abcdefULL * (i + 1 + repetition);
        v80_slash_ddr_stream::transfer(sim, source, 0, 256, true);
        v80_slash_ddr_stream::transfer(sim, destination, 0, 256, false);
        for (unsigned i = 0; i < 32; ++i)
          require(sim.predicted[memory_base + source + i * 8] ==
                      sim.predicted[memory_base + destination + i * 8],
                  "roundtrip differs after full platform/stream routing");
      }
      require(sim.reads == 96 && sim.writes == 96,
              "duplicate or missing external memory beats");
    }
    std::cout << name << ": PASS cycles=" << sim.cycles
              << " reads=" << sim.reads << " writes=" << sim.writes << '\n';
    sim.dut.final();
    return 0;
  } catch (const std::exception &error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}

#endif
