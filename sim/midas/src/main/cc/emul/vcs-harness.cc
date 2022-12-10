
#include <cassert>
#include <cmath>
#include <cstdio>

#include <exception>

#include <svdpi.h>

#include "context.h"
#include "simif_emul_vcs.h"

extern simif_emul_vcs_t *emulator;

/**
 * Helper to encode sequential elements into a packed structure.
 */
class StructWriter {
public:
  StructWriter(svBitVecVal *vec) : vec(vec) {}

  void putScalar(bool value) {
    svPutBitselBit(vec, offset, value);
    offset += 1;
  }

  void putScalarOrVector(uint64_t value, int width) {
    assert(width >= 1 && width <= 64);
    if (width == 1) {
      svPutBitselBit(vec, offset, value);
    } else if (width <= 32) {
      svPutPartselBit(vec, value, offset, width);
    } else {
      svPutPartselBit(vec, value, offset, 32);
      svPutPartselBit(vec, value >> 32, offset + 32, width - 32);
    }
    offset += width;
  }

  void putVector(void *data, int width) {
    for (size_t i = 0; i < width; i++) {
      svPutPartselBit(vec, ((uint32_t *)data)[i], offset, 32);
      offset += 32;
    }
  }

private:
  size_t offset = 0;
  svBitVecVal *vec;
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

/**
 * Helper class bridging the simulator AXI4 objects with arguments.
 */
template <unsigned ID_BITS,
          unsigned ADDR_BITS,
          unsigned STRB_BITS,
          unsigned BEAT_BYTES>
class AXI4 {
private:
  static constexpr size_t LENGTH(size_t bits) {
    return (bits + sizeof(svBitVecVal) * 8 - 1) / (sizeof(svBitVecVal) * 8);
  }

  static constexpr size_t DATA_SIZE = BEAT_BYTES / sizeof(svBitVecVal);

public:
  static void rev_tick(bool rst, mm_t *mem, const svBitVecVal *io) {
    StructReader r(io);
    auto b_ready = r.getScalar();
    auto r_ready = r.getScalar();
    auto w_last = r.getScalar();
    auto w_data = r.getVector(DATA_SIZE);
    auto w_strb = r.getScalarOrVector(STRB_BITS);
    auto w_valid = r.getScalar();
    auto aw_len = r.getScalarOrVector(8);
    auto aw_size = r.getScalarOrVector(3);
    auto aw_id = r.getScalarOrVector(ID_BITS);
    auto aw_addr = r.getScalarOrVector(ADDR_BITS);
    auto aw_valid = r.getScalar();
    auto ar_len = r.getScalarOrVector(8);
    auto ar_size = r.getScalarOrVector(3);
    auto ar_id = r.getScalarOrVector(ID_BITS);
    auto ar_addr = r.getScalarOrVector(ADDR_BITS);
    auto ar_valid = r.getScalar();

    mem->tick(rst,
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

  static void fwd_tick(bool rst, mmio_t *mmio, const svBitVecVal *io) {
    StructReader r(io);
    auto b_id = r.getScalarOrVector(ID_BITS);
    /*b_resp=*/r.getScalarOrVector(2);
    auto b_valid = r.getScalar();
    auto r_last = r.getScalar();
    auto r_data = r.getVector(DATA_SIZE);
    auto r_id = r.getScalarOrVector(ID_BITS);
    /*r_resp=*/r.getScalarOrVector(2);
    auto r_valid = r.getScalar();
    auto w_ready = r.getScalar();
    auto aw_ready = r.getScalar();
    auto ar_ready = r.getScalar();

    mmio->tick(rst,
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

  static void fwd_put(mm_t *mem, svBitVecVal *io) {
    StructWriter w(io);
    w.putScalarOrVector(mem->b_id(), ID_BITS);
    w.putScalarOrVector(mem->b_resp(), 2);
    w.putScalar(mem->b_valid());
    w.putScalar(mem->r_last());
    w.putVector(mem->r_data(), DATA_SIZE);
    w.putScalarOrVector(mem->r_id(), ID_BITS);
    w.putScalarOrVector(mem->r_resp(), 2);
    w.putScalar(mem->r_valid());
    w.putScalar(mem->w_ready());
    w.putScalar(mem->aw_ready());
    w.putScalar(mem->ar_ready());
  }

  static void rev_put(mmio_t *mmio, svBitVecVal *io) {
    StructWriter w(io);
    w.putScalar(mmio->b_ready());
    w.putScalar(mmio->r_ready());
    w.putScalar(mmio->w_last());
    w.putVector(mmio->w_data(), DATA_SIZE);
    w.putScalarOrVector(mmio->w_strb(), STRB_BITS);
    w.putScalar(mmio->w_valid());
    w.putScalarOrVector(mmio->aw_len(), 8);
    w.putScalarOrVector(mmio->aw_size(), 3);
    w.putScalarOrVector(mmio->aw_id(), ID_BITS);
    w.putScalarOrVector(mmio->aw_addr(), ADDR_BITS);
    w.putScalar(mmio->aw_valid());
    w.putScalarOrVector(mmio->ar_len(), 8);
    w.putScalarOrVector(mmio->ar_size(), 3);
    w.putScalarOrVector(mmio->ar_id(), ID_BITS);
    w.putScalarOrVector(mmio->ar_addr(), ADDR_BITS);
    w.putScalar(mmio->ar_valid());
  }
};

using Ctrl =
    AXI4<CTRL_ID_BITS, CTRL_ADDR_BITS, CTRL_STRB_BITS, CTRL_BEAT_BYTES>;

using CpuManagedAXI4 = AXI4<CPU_MANAGED_AXI4_ID_BITS,
                            CPU_MANAGED_AXI4_ADDR_BITS,
                            CPU_MANAGED_AXI4_STRB_BITS,
                            CPU_MANAGED_AXI4_BEAT_BYTES>;

using FpgaManagedAXI4 = AXI4<FPGA_MANAGED_AXI4_ID_BITS,
                             FPGA_MANAGED_AXI4_ADDR_BITS,
                             FPGA_MANAGED_AXI4_STRB_BITS,
                             FPGA_MANAGED_AXI4_BEAT_BYTES>;

using Memory = AXI4<MEM_ID_BITS, MEM_ADDR_BITS, MEM_STRB_BITS, MEM_BEAT_BYTES>;

extern "C" {
void tick(
    /* OUTPUT */ svBit *reset,
    /* OUTPUT */ svBit *fin,

    /* OUTPUT */ svBitVecVal *ctrl_out,
    /* INPUT  */ const svBitVecVal *ctrl_in,

    /* OUTPUT */ svBitVecVal *cpu_managed_axi4_out,
    /* INPUT  */ const svBitVecVal *cpu_managed_axi4_in,

    /* INPUT  */ const svBitVecVal *fpga_managed_axi4_in,
    /* OUTPUT */ svBitVecVal *fpga_managed_axi4_out,

    /* INPUT  */ const svBitVecVal *mem_0_in,
    /* OUTPUT */ svBitVecVal *mem_0_out,
    /* INPUT  */ const svBitVecVal *mem_1_in,
    /* OUTPUT */ svBitVecVal *mem_1_out,
    /* INPUT  */ const svBitVecVal *mem_2_in,
    /* OUTPUT */ svBitVecVal *mem_2_out,
    /* INPUT  */ const svBitVecVal *mem_3_in,
    /* OUTPUT */ svBitVecVal *mem_3_out) {
  try {
    // The driver ucontext is initialized before spawning the VCS
    // context, so these pointers should be initialized.
    assert(emulator != nullptr);
    assert(emulator->cpu_managed_axi4 != nullptr);
    assert(emulator->master != nullptr);

    bool rst = emulator->vcs_rst;

    Ctrl::fwd_tick(rst, emulator->master, ctrl_in);

#ifdef CPU_MANAGED_AXI4_PRESENT
    CpuManagedAXI4::fwd_tick(
        rst, emulator->cpu_managed_axi4, cpu_managed_axi4_in);
#endif // CPU_MANAGED_AXI4_PRESENT

#ifdef FPGA_MANAGED_AXI4_PRESENT
    FpgaManagedAXI4::rev_tick(rst, emulator->cpu_mem, fpga_managed_axi4_in);
#endif // FPGA_MANAGED_AXI4_PRESENT

    Memory::rev_tick(rst, emulator->slave[0], mem_0_in);
#ifdef MEM_HAS_CHANNEL1
    Memory::rev_tick(rst, emulator->slave[1], mem_1_in);
#endif
#ifdef MEM_HAS_CHANNEL2
    Memory::rev_tick(rst, emulator->slave[2], mem_2_in);
#endif
#ifdef MEM_HAS_CHANNEL3
    Memory::rev_tick(rst, emulator->slave[3], mem_3_in);
#endif

    if (!emulator->vcs_fin)
      emulator->host->switch_to();
    else
      emulator->vcs_fin = false;

    Ctrl::rev_put(emulator->master, ctrl_out);

#ifdef CPU_MANAGED_AXI4_PRESENT
    CpuManagedAXI4::rev_put(emulator->cpu_managed_axi4, cpu_managed_axi4_out);
#endif // CPU_MANAGED_AXI4_PRESENT

#ifdef FPGA_MANAGED_AXI4_PRESENT
    FpgaManagedAXI4::fwd_put(emulator->cpu_mem, fpga_managed_axi4_out);
#endif // FPGA_MANAGED_AXI4_PRESENT

    Memory::fwd_put(emulator->slave[0], mem_0_out);
#ifdef MEM_HAS_CHANNEL1
    Memory::fwd_put(emulator->slave[1], mem_1_out);
#endif
#ifdef MEM_HAS_CHANNEL2
    Memory::fwd_put(emulator->slave[2], mem_2_out);
#endif
#ifdef MEM_HAS_CHANNEL3
    Memory::fwd_put(emulator->slave[3], mem_3_out);
#endif

    *reset = emulator->vcs_rst;
    *fin = emulator->vcs_fin;

    emulator->main_time++;
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
