
#include <cassert>
#include <cmath>
#include <cstdio>

#include <exception>
#include <memory>

#include <signal.h>

#include <svdpi.h>

#include "emul/mm.h"
#include "emul/mmio.h"
#include "emul/simif_emul.h"

#ifdef VCS
#include "vc_hdrs.h"
#else
#include "Vemul.h"
#endif

/**
 * Helper to encode sequential elements into a packed structure.
 *
 * The writer does not use DPI methods as bit-sequence writes starting from
 * an uninitialised array can mix in uninitialised bits into the result. Since
 * Valgrind does not do bit-level taint tracking, this results in false
 * positives. Instead, the methods are specialised to only append values.
 */
class StructWriter {
public:
  StructWriter(svBitVecVal *vec) : vec(reinterpret_cast<uint64_t *>(vec)) {}

  void putScalar(bool value) { putScalarOrVector(value, 1); }

  void putScalarOrVector(uint64_t value, unsigned width) {
    assert(width >= 1 && width <= 64);

    unsigned idx = offset / 64;
    unsigned off = offset % 64;

    if (off == 0) {
      vec[idx] = value;
      offset += width;
      return;
    }

    unsigned remaining = 64 - off;
    if (remaining >= width) {
      vec[idx] |= value << off;
      offset += width;
      return;
    }

    vec[idx] |= value << off;
    vec[idx + 1] = value >> remaining;

    offset += width;
  }

  void putVector(void *data, unsigned width) {
    for (size_t i = 0; i < width; i++) {
      putScalarOrVector(((uint32_t *)data)[i], 32);
    }
  }

private:
  size_t offset = 0;
  uint64_t *vec;
};

/**
 * Helper to decide sequential elements from a packed structure.
 */
class StructReader {
public:
  StructReader(const svBitVecVal *vec) : vec(vec) {}

  bool getScalar() { return svGetBitselBit(vec, offset++); }

  uint64_t getScalarOrVector(int width) {
    assert(width >= 1 && width <= 64);
    if (width == 1) {
      return svGetBitselBit(vec, offset++);
    }
    if (width <= 32) {
      svBitVecVal elem;
      svGetPartselBit(&elem, vec, offset, width);
      offset += width;
      return elem;
    }
    svBitVecVal lo, hi;
    svGetPartselBit(&lo, vec, offset, 32);
    svGetPartselBit(&hi, vec, offset + 32, width - 32);
    offset += width;
    return lo | (((uint64_t)hi) << 32);
  }

  std::vector<uint32_t> getVector(int width) {
    std::vector<uint32_t> buffer(width);
    for (size_t i = 0; i < width; i++) {
      svGetPartselBit(&buffer[i], vec, offset, 32);
      offset += 32;
    }
    return buffer;
  }

private:
  size_t offset = 0;
  const svBitVecVal *vec;
};

namespace AXI4 {
void rev_tick(bool rst, mm_t &mem, const svBitVecVal *io) {
  const AXI4Config &conf = mem.get_config();
  StructReader r(io);
  auto b_ready = r.getScalar();
  auto r_ready = r.getScalar();
  auto w_last = r.getScalar();
  auto w_data = r.getVector(conf.get_data_size());
  auto w_strb = r.getScalarOrVector(conf.strb_bits());
  auto w_valid = r.getScalar();
  auto aw_len = r.getScalarOrVector(8);
  auto aw_size = r.getScalarOrVector(3);
  auto aw_id = r.getScalarOrVector(conf.id_bits);
  auto aw_addr = r.getScalarOrVector(conf.addr_bits);
  auto aw_valid = r.getScalar();
  auto ar_len = r.getScalarOrVector(8);
  auto ar_size = r.getScalarOrVector(3);
  auto ar_id = r.getScalarOrVector(conf.id_bits);
  auto ar_addr = r.getScalarOrVector(conf.addr_bits);
  auto ar_valid = r.getScalar();

  mem.tick(rst,
           ar_valid,
           ar_addr,
           ar_id,
           ar_size,
           ar_len,
           aw_valid,
           aw_addr,
           aw_id,
           aw_size,
           aw_len,
           w_valid,
           w_strb,
           w_data,
           w_last,
           r_ready,
           b_ready);
}

void fwd_tick(bool rst, mmio_t &mmio, const svBitVecVal *io) {
  const AXI4Config &conf = mmio.get_config();
  StructReader r(io);
  auto b_id = r.getScalarOrVector(conf.id_bits);
  /*b_resp=*/r.getScalarOrVector(2);
  auto b_valid = r.getScalar();
  auto r_last = r.getScalar();
  auto r_data = r.getVector(conf.get_data_size());
  auto r_id = r.getScalarOrVector(conf.id_bits);
  /*r_resp=*/r.getScalarOrVector(2);
  auto r_valid = r.getScalar();
  auto w_ready = r.getScalar();
  auto aw_ready = r.getScalar();
  auto ar_ready = r.getScalar();

  mmio.tick(rst,
            ar_ready,
            aw_ready,
            w_ready,
            r_id,
            r_data,
            r_last,
            r_valid,
            b_id,
            b_valid);
}

void fwd_put(mm_t &mem, svBitVecVal *io) {
  const AXI4Config &conf = mem.get_config();
  StructWriter w(io);
  w.putScalarOrVector(mem.b_id(), conf.id_bits);
  w.putScalarOrVector(mem.b_resp(), 2);
  w.putScalar(mem.b_valid());
  w.putScalar(mem.r_last());
  w.putVector(mem.r_data(), conf.get_data_size());
  w.putScalarOrVector(mem.r_id(), conf.id_bits);
  w.putScalarOrVector(mem.r_resp(), 2);
  w.putScalar(mem.r_valid());
  w.putScalar(mem.w_ready());
  w.putScalar(mem.aw_ready());
  w.putScalar(mem.ar_ready());
}

void rev_put(mmio_t &mmio, svBitVecVal *io) {
  const AXI4Config &conf = mmio.get_config();
  StructWriter w(io);
  w.putScalar(mmio.b_ready());
  w.putScalar(mmio.r_ready());
  w.putScalar(mmio.w_last());
  w.putVector(mmio.w_data(), conf.get_data_size());
  w.putScalarOrVector(mmio.w_strb(), conf.strb_bits());
  w.putScalar(mmio.w_valid());
  w.putScalarOrVector(mmio.aw_len(), 8);
  w.putScalarOrVector(mmio.aw_size(), 3);
  w.putScalarOrVector(mmio.aw_id(), conf.id_bits);
  w.putScalarOrVector(mmio.aw_addr(), conf.addr_bits);
  w.putScalar(mmio.aw_valid());
  w.putScalarOrVector(mmio.ar_len(), 8);
  w.putScalarOrVector(mmio.ar_size(), 3);
  w.putScalarOrVector(mmio.ar_id(), conf.id_bits);
  w.putScalarOrVector(mmio.ar_addr(), conf.addr_bits);
  w.putScalar(mmio.ar_valid());
}
} // namespace AXI4

extern simif_emul_t *simulator;

extern "C" {
void simulator_tick(
    /* INPUT  */ const svBit reset,
    /* OUTPUT */ svBit *fin,

    /* INPUT  */ const svBitVecVal *ctrl_in,
    /* INPUT  */ const svBitVecVal *cpu_managed_axi4_in,
    /* INPUT  */ const svBitVecVal *fpga_managed_axi4_in,
    /* INPUT  */ const svBitVecVal *mem_0_in,
    /* INPUT  */ const svBitVecVal *mem_1_in,
    /* INPUT  */ const svBitVecVal *mem_2_in,
    /* INPUT  */ const svBitVecVal *mem_3_in,

    /* OUTPUT */ svBitVecVal *ctrl_out,
    /* OUTPUT */ svBitVecVal *cpu_managed_axi4_out,
    /* OUTPUT */ svBitVecVal *fpga_managed_axi4_out,
    /* OUTPUT */ svBitVecVal *mem_0_out,
    /* OUTPUT */ svBitVecVal *mem_1_out,
    /* OUTPUT */ svBitVecVal *mem_2_out,
    /* OUTPUT */ svBitVecVal *mem_3_out) {
  try {
    // The driver ucontext is initialized before spawning the VCS
    // context, so these pointers should be initialized.
    assert(simulator != nullptr);
    assert(simulator->master != nullptr);

    mmio_t *cpu_managed_axi4 = simulator->get_cpu_managed_axi4();
    mm_t *fpga_managed_axi4 = simulator->get_fpga_managed_axi4();

    std::array<svBitVecVal *, 4> mem_out{
        mem_0_out, mem_1_out, mem_2_out, mem_3_out};
    std::array<const svBitVecVal *, 4> mem_in{
        mem_0_in, mem_1_in, mem_2_in, mem_3_in};

    AXI4::fwd_tick(reset, *simulator->master, ctrl_in);

    if (cpu_managed_axi4) {
      AXI4::fwd_tick(reset, *cpu_managed_axi4, cpu_managed_axi4_in);
    }
    if (fpga_managed_axi4) {
      AXI4::rev_tick(reset, *fpga_managed_axi4, fpga_managed_axi4_in);
    }
    for (size_t i = 0, n = simulator->slave.size(); i < n; ++i) {
      AXI4::rev_tick(reset, *simulator->slave[i], mem_in[i]);
    }

    *fin = simulator->to_sim();

    AXI4::rev_put(*simulator->master, ctrl_out);
    if (cpu_managed_axi4) {
      AXI4::rev_put(*cpu_managed_axi4, cpu_managed_axi4_out);
    }
    if (fpga_managed_axi4) {
      AXI4::fwd_put(*fpga_managed_axi4, fpga_managed_axi4_out);
    }
    for (size_t i = 0, n = simulator->slave.size(); i < n; ++i) {
      AXI4::fwd_put(*simulator->slave[i], mem_out[i]);
    }

#ifdef VCS
    if (*fin) {
      int exit_code = simulator->end();
      delete simulator;
      simulator = nullptr;
      if (exit_code)
        exit(exit_code);
    }
#endif
  } catch (std::exception &e) {
    fprintf(stderr, "Caught Exception headed for VCS: %s.\n", e.what());
    abort();
  } catch (...) {
    // seriously, VCS will give you an unhelpful message if you let an exception
    // propagate catch it here and if we hit this, I can go rememeber how to
    // unwind the stack to print a trace
    fprintf(stderr, "Caught non std::exception headed for VCS\n");
    abort();
  }
}
}
