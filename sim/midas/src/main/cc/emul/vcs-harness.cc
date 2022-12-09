
#include <DirectC.h>
#include <cassert>
#include <cmath>
#include <exception>
#include <stdio.h>

#include "context.h"
#include "simif_emul_vcs.h"

extern simif_emul_vcs_t *emulator;

constexpr size_t CTRL_DATA_SIZE = CTRL_BEAT_BYTES / sizeof(uint32_t);
constexpr size_t CPU_MANAGED_AXI4_DATA_SIZE =
    CPU_MANAGED_AXI4_BEAT_BYTES / sizeof(uint32_t);
constexpr size_t CPU_MANAGED_AXI4_STRB_SIZE =
    (CPU_MANAGED_AXI4_BEAT_BYTES / 8 + sizeof(uint32_t) - 1) / sizeof(uint32_t);
constexpr size_t FPGA_MANAGED_AXI4_DATA_SIZE =
    (FPGA_MANAGED_AXI4_DATA_BITS / 8) / sizeof(uint32_t);
constexpr size_t FPGA_MANAGED_AXI4_STRB_SIZE =
    ((FPGA_MANAGED_AXI4_DATA_BITS / 8) / 8 + sizeof(uint32_t) - 1) /
    sizeof(uint32_t);
constexpr size_t MEM_DATA_SIZE = MEM_BEAT_BYTES / sizeof(uint32_t);

/**
 * @brief get a uint64_t from a vc_handle that may be a scalar or vector
 *
 * vc_handles for single bit vs multibit values need to be accessed at runtime
 * with different methods. This handles that for fields that might be 1-bit wide
 *
 * In practise this is just the ID field, so return uint64_t which is what mm
 * expects
 *
 * @param h the vc_handle
 * @param width the expected width of the bitvector
 * @return uint64_t the bitvector encoded as uint64_t
 */
uint64_t getScalarOrVector(const vc_handle &h, int width) {
  assert(width >= 1 && width <= 64);
  return (width == 1) ? vc_getScalar(h) : vc_4stVectorRef(h)->d;
}

/**
 * @brief Put the LSBs of @value into a vc_handle that may be a vector or
 * scalar.
 *
 * @param h the vc_handle
 * @param value a uint64_t whose LSBs contain a bitvector to drive onto the
 * handle
 * @param width the width of the bitvector
 */
void putScalarOrVector(const vc_handle &h, uint64_t value, int width) {
  assert(width >= 1 && width <= 64);
  if (width == 1) {
    vc_putScalar(h, value & 1);
  } else {
    vec32 md[sizeof(uint64_t) / sizeof(uint32_t)];
    md[0].c = 0;
    md[0].d = (uint32_t)value;
    md[1].c = 0;
    md[1].d = (uint32_t)(value >> 32);
    vc_put4stVector(h, md);
  }
}

extern "C" {
void tick(vc_handle reset,
          vc_handle fin,

          vc_handle ctrl_ar_valid,
          vc_handle ctrl_ar_ready,
          vc_handle ctrl_ar_bits_addr,
          vc_handle ctrl_ar_bits_id,
          vc_handle ctrl_ar_bits_size,
          vc_handle ctrl_ar_bits_len,

          vc_handle ctrl_aw_valid,
          vc_handle ctrl_aw_ready,
          vc_handle ctrl_aw_bits_addr,
          vc_handle ctrl_aw_bits_id,
          vc_handle ctrl_aw_bits_size,
          vc_handle ctrl_aw_bits_len,

          vc_handle ctrl_w_valid,
          vc_handle ctrl_w_ready,
          vc_handle ctrl_w_bits_strb,
          vc_handle ctrl_w_bits_data,
          vc_handle ctrl_w_bits_last,

          vc_handle ctrl_r_valid,
          vc_handle ctrl_r_ready,
          vc_handle ctrl_r_bits_resp,
          vc_handle ctrl_r_bits_id,
          vc_handle ctrl_r_bits_data,
          vc_handle ctrl_r_bits_last,

          vc_handle ctrl_b_valid,
          vc_handle ctrl_b_ready,
          vc_handle ctrl_b_bits_resp,
          vc_handle ctrl_b_bits_id,

          vc_handle cpu_managed_axi4_ar_valid,
          vc_handle cpu_managed_axi4_ar_ready,
          vc_handle cpu_managed_axi4_ar_bits_addr,
          vc_handle cpu_managed_axi4_ar_bits_id,
          vc_handle cpu_managed_axi4_ar_bits_size,
          vc_handle cpu_managed_axi4_ar_bits_len,

          vc_handle cpu_managed_axi4_aw_valid,
          vc_handle cpu_managed_axi4_aw_ready,
          vc_handle cpu_managed_axi4_aw_bits_addr,
          vc_handle cpu_managed_axi4_aw_bits_id,
          vc_handle cpu_managed_axi4_aw_bits_size,
          vc_handle cpu_managed_axi4_aw_bits_len,

          vc_handle cpu_managed_axi4_w_valid,
          vc_handle cpu_managed_axi4_w_ready,
          vc_handle cpu_managed_axi4_w_bits_strb,
          vc_handle cpu_managed_axi4_w_bits_data,
          vc_handle cpu_managed_axi4_w_bits_last,

          vc_handle cpu_managed_axi4_r_valid,
          vc_handle cpu_managed_axi4_r_ready,
          vc_handle cpu_managed_axi4_r_bits_resp,
          vc_handle cpu_managed_axi4_r_bits_id,
          vc_handle cpu_managed_axi4_r_bits_data,
          vc_handle cpu_managed_axi4_r_bits_last,

          vc_handle cpu_managed_axi4_b_valid,
          vc_handle cpu_managed_axi4_b_ready,
          vc_handle cpu_managed_axi4_b_bits_resp,
          vc_handle cpu_managed_axi4_b_bits_id,

          vc_handle fpga_managed_axi4_ar_valid,
          vc_handle fpga_managed_axi4_ar_ready,
          vc_handle fpga_managed_axi4_ar_bits_addr,
          vc_handle fpga_managed_axi4_ar_bits_id,
          vc_handle fpga_managed_axi4_ar_bits_size,
          vc_handle fpga_managed_axi4_ar_bits_len,

          vc_handle fpga_managed_axi4_aw_valid,
          vc_handle fpga_managed_axi4_aw_ready,
          vc_handle fpga_managed_axi4_aw_bits_addr,
          vc_handle fpga_managed_axi4_aw_bits_id,
          vc_handle fpga_managed_axi4_aw_bits_size,
          vc_handle fpga_managed_axi4_aw_bits_len,

          vc_handle fpga_managed_axi4_w_valid,
          vc_handle fpga_managed_axi4_w_ready,
          vc_handle fpga_managed_axi4_w_bits_strb,
          vc_handle fpga_managed_axi4_w_bits_data,
          vc_handle fpga_managed_axi4_w_bits_last,

          vc_handle fpga_managed_axi4_r_valid,
          vc_handle fpga_managed_axi4_r_ready,
          vc_handle fpga_managed_axi4_r_bits_resp,
          vc_handle fpga_managed_axi4_r_bits_id,
          vc_handle fpga_managed_axi4_r_bits_data,
          vc_handle fpga_managed_axi4_r_bits_last,

          vc_handle fpga_managed_axi4_b_valid,
          vc_handle fpga_managed_axi4_b_ready,
          vc_handle fpga_managed_axi4_b_bits_resp,
          vc_handle fpga_managed_axi4_b_bits_id,

          vc_handle mem_0_ar_valid,
          vc_handle mem_0_ar_ready,
          vc_handle mem_0_ar_bits_addr,
          vc_handle mem_0_ar_bits_id,
          vc_handle mem_0_ar_bits_size,
          vc_handle mem_0_ar_bits_len,

          vc_handle mem_0_aw_valid,
          vc_handle mem_0_aw_ready,
          vc_handle mem_0_aw_bits_addr,
          vc_handle mem_0_aw_bits_id,
          vc_handle mem_0_aw_bits_size,
          vc_handle mem_0_aw_bits_len,

          vc_handle mem_0_w_valid,
          vc_handle mem_0_w_ready,
          vc_handle mem_0_w_bits_strb,
          vc_handle mem_0_w_bits_data,
          vc_handle mem_0_w_bits_last,

          vc_handle mem_0_r_valid,
          vc_handle mem_0_r_ready,
          vc_handle mem_0_r_bits_resp,
          vc_handle mem_0_r_bits_id,
          vc_handle mem_0_r_bits_data,
          vc_handle mem_0_r_bits_last,

          vc_handle mem_0_b_valid,
          vc_handle mem_0_b_ready,
          vc_handle mem_0_b_bits_resp,
          vc_handle mem_0_b_bits_id,

          vc_handle mem_1_ar_valid,
          vc_handle mem_1_ar_ready,
          vc_handle mem_1_ar_bits_addr,
          vc_handle mem_1_ar_bits_id,
          vc_handle mem_1_ar_bits_size,
          vc_handle mem_1_ar_bits_len,

          vc_handle mem_1_aw_valid,
          vc_handle mem_1_aw_ready,
          vc_handle mem_1_aw_bits_addr,
          vc_handle mem_1_aw_bits_id,
          vc_handle mem_1_aw_bits_size,
          vc_handle mem_1_aw_bits_len,

          vc_handle mem_1_w_valid,
          vc_handle mem_1_w_ready,
          vc_handle mem_1_w_bits_strb,
          vc_handle mem_1_w_bits_data,
          vc_handle mem_1_w_bits_last,

          vc_handle mem_1_r_valid,
          vc_handle mem_1_r_ready,
          vc_handle mem_1_r_bits_resp,
          vc_handle mem_1_r_bits_id,
          vc_handle mem_1_r_bits_data,
          vc_handle mem_1_r_bits_last,

          vc_handle mem_1_b_valid,
          vc_handle mem_1_b_ready,
          vc_handle mem_1_b_bits_resp,
          vc_handle mem_1_b_bits_id,

          vc_handle mem_2_ar_valid,
          vc_handle mem_2_ar_ready,
          vc_handle mem_2_ar_bits_addr,
          vc_handle mem_2_ar_bits_id,
          vc_handle mem_2_ar_bits_size,
          vc_handle mem_2_ar_bits_len,

          vc_handle mem_2_aw_valid,
          vc_handle mem_2_aw_ready,
          vc_handle mem_2_aw_bits_addr,
          vc_handle mem_2_aw_bits_id,
          vc_handle mem_2_aw_bits_size,
          vc_handle mem_2_aw_bits_len,

          vc_handle mem_2_w_valid,
          vc_handle mem_2_w_ready,
          vc_handle mem_2_w_bits_strb,
          vc_handle mem_2_w_bits_data,
          vc_handle mem_2_w_bits_last,

          vc_handle mem_2_r_valid,
          vc_handle mem_2_r_ready,
          vc_handle mem_2_r_bits_resp,
          vc_handle mem_2_r_bits_id,
          vc_handle mem_2_r_bits_data,
          vc_handle mem_2_r_bits_last,

          vc_handle mem_2_b_valid,
          vc_handle mem_2_b_ready,
          vc_handle mem_2_b_bits_resp,
          vc_handle mem_2_b_bits_id,

          vc_handle mem_3_ar_valid,
          vc_handle mem_3_ar_ready,
          vc_handle mem_3_ar_bits_addr,
          vc_handle mem_3_ar_bits_id,
          vc_handle mem_3_ar_bits_size,
          vc_handle mem_3_ar_bits_len,

          vc_handle mem_3_aw_valid,
          vc_handle mem_3_aw_ready,
          vc_handle mem_3_aw_bits_addr,
          vc_handle mem_3_aw_bits_id,
          vc_handle mem_3_aw_bits_size,
          vc_handle mem_3_aw_bits_len,

          vc_handle mem_3_w_valid,
          vc_handle mem_3_w_ready,
          vc_handle mem_3_w_bits_strb,
          vc_handle mem_3_w_bits_data,
          vc_handle mem_3_w_bits_last,

          vc_handle mem_3_r_valid,
          vc_handle mem_3_r_ready,
          vc_handle mem_3_r_bits_resp,
          vc_handle mem_3_r_bits_id,
          vc_handle mem_3_r_bits_data,
          vc_handle mem_3_r_bits_last,

          vc_handle mem_3_b_valid,
          vc_handle mem_3_b_ready,
          vc_handle mem_3_b_bits_resp,
          vc_handle mem_3_b_bits_id) {
  try {
    // The driver ucontext is initialized before spawning the VCS
    // context, so these pointers should be initialized.
    assert(emulator->cpu_managed_axi4 != nullptr);
    assert(emulator->master != nullptr);

    static_assert(CPU_MANAGED_AXI4_STRB_SIZE <= 2);

    uint32_t ctrl_r_data[CTRL_DATA_SIZE];
    for (size_t i = 0; i < CTRL_DATA_SIZE; i++) {
      ctrl_r_data[i] = vc_4stVectorRef(ctrl_r_bits_data)[i].d;
    }

    emulator->master->tick(emulator->vcs_rst,
                           vc_getScalar(ctrl_ar_ready),
                           vc_getScalar(ctrl_aw_ready),
                           vc_getScalar(ctrl_w_ready),
                           getScalarOrVector(ctrl_r_bits_id, CTRL_ID_BITS),
                           ctrl_r_data,
                           vc_getScalar(ctrl_r_bits_last),
                           vc_getScalar(ctrl_r_valid),
                           getScalarOrVector(ctrl_b_bits_id, CTRL_ID_BITS),
                           vc_getScalar(ctrl_b_valid));

#ifdef CPU_MANAGED_AXI4_PRESENT
    assert(CPU_MANAGED_AXI4_STRB_SIZE <= 2);
    uint32_t cpu_managed_axi4_r_data[CPU_MANAGED_AXI4_DATA_SIZE];
    for (size_t i = 0; i < CPU_MANAGED_AXI4_DATA_SIZE; i++) {
      cpu_managed_axi4_r_data[i] =
          vc_4stVectorRef(cpu_managed_axi4_r_bits_data)[i].d;
    }

    emulator->cpu_managed_axi4->tick(
        emulator->vcs_rst,
        vc_getScalar(cpu_managed_axi4_ar_ready),
        vc_getScalar(cpu_managed_axi4_aw_ready),
        vc_getScalar(cpu_managed_axi4_w_ready),
        vc_4stVectorRef(cpu_managed_axi4_r_bits_id)->d,
        cpu_managed_axi4_r_data,
        vc_getScalar(cpu_managed_axi4_r_bits_last),
        vc_getScalar(cpu_managed_axi4_r_valid),
        vc_4stVectorRef(cpu_managed_axi4_b_bits_id)->d,
        vc_getScalar(cpu_managed_axi4_b_valid));
#endif // CPU_MANAGED_AXI4_PRESENT

#ifdef FPGA_MANAGED_AXI4_PRESENT
    uint32_t fpga_managed_axi4_w_data[FPGA_MANAGED_AXI4_DATA_SIZE];
    for (size_t i = 0; i < FPGA_MANAGED_AXI4_DATA_SIZE; i++) {
      fpga_managed_axi4_w_data[i] =
          vc_4stVectorRef(fpga_managed_axi4_w_bits_data)[i].d;
    }

    uint64_t fpga_managed_axi4_w_strb;
    static_assert(FPGA_MANAGED_AXI4_STRB_SIZE <= 2);
    for (size_t i = 0; i < FPGA_MANAGED_AXI4_STRB_SIZE; i++) {
      ((uint32_t *)&fpga_managed_axi4_w_strb)[i] =
          vc_4stVectorRef(fpga_managed_axi4_w_bits_strb)[i].d;
    }

    emulator->cpu_mem->tick(emulator->vcs_rst,
                            vc_getScalar(fpga_managed_axi4_ar_valid),
                            vc_4stVectorRef(fpga_managed_axi4_ar_bits_addr)->d,
                            getScalarOrVector(fpga_managed_axi4_ar_bits_id,
                                              FPGA_MANAGED_AXI4_ID_BITS),
                            vc_4stVectorRef(fpga_managed_axi4_ar_bits_size)->d,
                            vc_4stVectorRef(fpga_managed_axi4_ar_bits_len)->d,

                            vc_getScalar(fpga_managed_axi4_aw_valid),
                            vc_4stVectorRef(fpga_managed_axi4_aw_bits_addr)->d,
                            getScalarOrVector(fpga_managed_axi4_aw_bits_id,
                                              FPGA_MANAGED_AXI4_ID_BITS),
                            vc_4stVectorRef(fpga_managed_axi4_aw_bits_size)->d,
                            vc_4stVectorRef(fpga_managed_axi4_aw_bits_len)->d,

                            vc_getScalar(fpga_managed_axi4_w_valid),
                            fpga_managed_axi4_w_strb,
                            fpga_managed_axi4_w_data,
                            vc_getScalar(fpga_managed_axi4_w_bits_last),

                            vc_getScalar(fpga_managed_axi4_r_ready),
                            vc_getScalar(fpga_managed_axi4_b_ready));
#endif // FPGA_MANAGED_AXI4_PRESENT

#define MEMORY_CHANNEL_TICK(IDX)                                               \
  uint32_t mem_##IDX##_w_data[MEM_DATA_SIZE];                                  \
  for (size_t i = 0; i < MEM_DATA_SIZE; i++) {                                 \
    mem_##IDX##_w_data[i] = vc_4stVectorRef(mem_##IDX##_w_bits_data)[i].d;     \
  }                                                                            \
                                                                               \
  emulator->slave[IDX]->tick(emulator->vcs_rst,                                \
                             vc_getScalar(mem_##IDX##_ar_valid),               \
                             vc_4stVectorRef(mem_##IDX##_ar_bits_addr)->d,     \
                             vc_4stVectorRef(mem_##IDX##_ar_bits_id)->d,       \
                             vc_4stVectorRef(mem_##IDX##_ar_bits_size)->d,     \
                             vc_4stVectorRef(mem_##IDX##_ar_bits_len)->d,      \
                                                                               \
                             vc_getScalar(mem_##IDX##_aw_valid),               \
                             vc_4stVectorRef(mem_##IDX##_aw_bits_addr)->d,     \
                             vc_4stVectorRef(mem_##IDX##_aw_bits_id)->d,       \
                             vc_4stVectorRef(mem_##IDX##_aw_bits_size)->d,     \
                             vc_4stVectorRef(mem_##IDX##_aw_bits_len)->d,      \
                                                                               \
                             vc_getScalar(mem_##IDX##_w_valid),                \
                             vc_4stVectorRef(mem_##IDX##_w_bits_strb)->d,      \
                             mem_##IDX##_w_data,                               \
                             vc_getScalar(mem_##IDX##_w_bits_last),            \
                                                                               \
                             vc_getScalar(mem_##IDX##_r_ready),                \
                             vc_getScalar(mem_##IDX##_b_ready));

    MEMORY_CHANNEL_TICK(0)
#ifdef MEM_HAS_CHANNEL1
    MEMORY_CHANNEL_TICK(1)
#endif
#ifdef MEM_HAS_CHANNEL2
    MEMORY_CHANNEL_TICK(2)
#endif
#ifdef MEM_HAS_CHANNEL3
    MEMORY_CHANNEL_TICK(3)
#endif

    if (!emulator->vcs_fin)
      emulator->host->switch_to();
    else
      emulator->vcs_fin = false;

    vc_putScalar(ctrl_aw_valid, emulator->master->aw_valid());
    vc_putScalar(ctrl_ar_valid, emulator->master->ar_valid());
    vc_putScalar(ctrl_w_valid, emulator->master->w_valid());
    vc_putScalar(ctrl_w_bits_last, emulator->master->w_last());
    vc_putScalar(ctrl_r_ready, emulator->master->r_ready());
    vc_putScalar(ctrl_b_ready, emulator->master->b_ready());

    vec32 md[CTRL_DATA_SIZE];
    md[0].c = 0;
    md[0].d = emulator->master->aw_addr();
    vc_put4stVector(ctrl_aw_bits_addr, md);
    md[0].c = 0;
    md[0].d = emulator->master->aw_size();
    vc_put4stVector(ctrl_aw_bits_size, md);
    md[0].c = 0;
    md[0].d = emulator->master->aw_len();
    vc_put4stVector(ctrl_aw_bits_len, md);
    md[0].c = 0;
    md[0].d = emulator->master->ar_addr();
    vc_put4stVector(ctrl_ar_bits_addr, md);
    md[0].c = 0;
    md[0].d = emulator->master->ar_size();
    vc_put4stVector(ctrl_ar_bits_size, md);
    md[0].c = 0;
    md[0].d = emulator->master->ar_len();
    vc_put4stVector(ctrl_ar_bits_len, md);
    md[0].c = 0;
    md[0].d = emulator->master->w_strb();
    vc_put4stVector(ctrl_w_bits_strb, md);

    for (size_t i = 0; i < CTRL_DATA_SIZE; i++) {
      md[i].c = 0;
      md[i].d = ((uint32_t *)emulator->master->w_data())[i];
    }
    vc_put4stVector(ctrl_w_bits_data, md);

    putScalarOrVector(ctrl_aw_bits_id, emulator->master->aw_id(), CTRL_ID_BITS);
    putScalarOrVector(ctrl_ar_bits_id, emulator->master->ar_id(), CTRL_ID_BITS);

#ifdef CPU_MANAGED_AXI4_PRESENT
    vc_putScalar(cpu_managed_axi4_aw_valid,
                 emulator->cpu_managed_axi4->aw_valid());
    vc_putScalar(cpu_managed_axi4_ar_valid,
                 emulator->cpu_managed_axi4->ar_valid());
    vc_putScalar(cpu_managed_axi4_w_valid,
                 emulator->cpu_managed_axi4->w_valid());
    vc_putScalar(cpu_managed_axi4_w_bits_last,
                 emulator->cpu_managed_axi4->w_last());
    vc_putScalar(cpu_managed_axi4_r_ready,
                 emulator->cpu_managed_axi4->r_ready());
    vc_putScalar(cpu_managed_axi4_b_ready,
                 emulator->cpu_managed_axi4->b_ready());

    vec32 dd[CPU_MANAGED_AXI4_DATA_SIZE];
    dd[0].c = 0;
    dd[0].d = emulator->cpu_managed_axi4->aw_id();
    vc_put4stVector(cpu_managed_axi4_aw_bits_id, dd);
    dd[0].c = 0;
    dd[0].d = emulator->cpu_managed_axi4->aw_addr();
    dd[1].c = 0;
    dd[1].d = emulator->cpu_managed_axi4->aw_addr() >> 32;
    vc_put4stVector(cpu_managed_axi4_aw_bits_addr, dd);
    dd[0].c = 0;
    dd[0].d = emulator->cpu_managed_axi4->aw_size();
    vc_put4stVector(cpu_managed_axi4_aw_bits_size, dd);
    dd[0].c = 0;
    dd[0].d = emulator->cpu_managed_axi4->aw_len();
    vc_put4stVector(cpu_managed_axi4_aw_bits_len, dd);
    dd[0].c = 0;
    dd[0].d = emulator->cpu_managed_axi4->ar_id();
    vc_put4stVector(cpu_managed_axi4_ar_bits_id, dd);
    dd[0].c = 0;
    dd[0].d = emulator->cpu_managed_axi4->ar_addr();
    dd[1].c = 0;
    dd[1].d = emulator->cpu_managed_axi4->ar_addr() >> 32;
    vc_put4stVector(cpu_managed_axi4_ar_bits_addr, dd);
    dd[0].c = 0;
    dd[0].d = emulator->cpu_managed_axi4->ar_size();
    vc_put4stVector(cpu_managed_axi4_ar_bits_size, dd);
    dd[0].c = 0;
    dd[0].d = emulator->cpu_managed_axi4->ar_len();
    vc_put4stVector(cpu_managed_axi4_ar_bits_len, dd);

    auto strb = emulator->cpu_managed_axi4->w_strb();
    for (size_t i = 0; i < CPU_MANAGED_AXI4_STRB_SIZE; i++) {
      dd[i].c = 0;
      dd[i].d = ((uint32_t *)(&strb))[i];
    }
    vc_put4stVector(cpu_managed_axi4_w_bits_strb, dd);

    for (size_t i = 0; i < CPU_MANAGED_AXI4_DATA_SIZE; i++) {
      dd[i].c = 0;
      dd[i].d = ((uint32_t *)emulator->cpu_managed_axi4->w_data())[i];
    }
    vc_put4stVector(cpu_managed_axi4_w_bits_data, dd);
#endif // CPU_MANAGED_AXI4_PRESENT

#ifdef FPGA_MANAGED_AXI4_PRESENT
    vc_putScalar(fpga_managed_axi4_aw_ready, emulator->cpu_mem->aw_ready());
    vc_putScalar(fpga_managed_axi4_ar_ready, emulator->cpu_mem->ar_ready());
    vc_putScalar(fpga_managed_axi4_w_ready, emulator->cpu_mem->w_ready());
    vc_putScalar(fpga_managed_axi4_b_valid, emulator->cpu_mem->b_valid());
    vc_putScalar(fpga_managed_axi4_r_valid, emulator->cpu_mem->r_valid());
    vc_putScalar(fpga_managed_axi4_r_bits_last, emulator->cpu_mem->r_last());

    vec32 fpga_managed_axi4d[FPGA_MANAGED_AXI4_DATA_SIZE];
    fpga_managed_axi4d[0].c = 0;
    fpga_managed_axi4d[0].d = emulator->cpu_mem->b_resp();
    vc_put4stVector(fpga_managed_axi4_b_bits_resp, fpga_managed_axi4d);
    fpga_managed_axi4d[0].c = 0;
    fpga_managed_axi4d[0].d = emulator->cpu_mem->r_resp();
    vc_put4stVector(fpga_managed_axi4_r_bits_resp, fpga_managed_axi4d);

    for (size_t i = 0; i < FPGA_MANAGED_AXI4_DATA_SIZE; i++) {
      fpga_managed_axi4d[i].c = 0;
      fpga_managed_axi4d[i].d = ((uint32_t *)emulator->cpu_mem->r_data())[i];
    }
    vc_put4stVector(fpga_managed_axi4_r_bits_data, fpga_managed_axi4d);

    putScalarOrVector(fpga_managed_axi4_b_bits_id,
                      emulator->cpu_mem->b_id(),
                      FPGA_MANAGED_AXI4_ID_BITS);
    putScalarOrVector(fpga_managed_axi4_r_bits_id,
                      emulator->cpu_mem->r_id(),
                      FPGA_MANAGED_AXI4_ID_BITS);
#endif // FPGA_MANAGED_AXI4_PRESENT

#define MEMORY_CHANNEL_PROP(IDX)                                               \
  vc_putScalar(mem_##IDX##_aw_ready, emulator->slave[IDX]->aw_ready());        \
  vc_putScalar(mem_##IDX##_ar_ready, emulator->slave[IDX]->ar_ready());        \
  vc_putScalar(mem_##IDX##_w_ready, emulator->slave[IDX]->w_ready());          \
  vc_putScalar(mem_##IDX##_b_valid, emulator->slave[IDX]->b_valid());          \
  vc_putScalar(mem_##IDX##_r_valid, emulator->slave[IDX]->r_valid());          \
  vc_putScalar(mem_##IDX##_r_bits_last, emulator->slave[IDX]->r_last());       \
                                                                               \
  vec32 sd_##IDX[MEM_DATA_SIZE];                                               \
  sd_##IDX[0].c = 0;                                                           \
  sd_##IDX[0].d = emulator->slave[IDX]->b_id();                                \
  vc_put4stVector(mem_##IDX##_b_bits_id, sd_##IDX);                            \
  sd_##IDX[0].c = 0;                                                           \
  sd_##IDX[0].d = emulator->slave[IDX]->b_resp();                              \
  vc_put4stVector(mem_##IDX##_b_bits_resp, sd_##IDX);                          \
  sd_##IDX[0].c = 0;                                                           \
  sd_##IDX[0].d = emulator->slave[IDX]->r_id();                                \
  vc_put4stVector(mem_##IDX##_r_bits_id, sd_##IDX);                            \
  sd_##IDX[0].c = 0;                                                           \
  sd_##IDX[0].d = emulator->slave[IDX]->r_resp();                              \
  vc_put4stVector(mem_##IDX##_r_bits_resp, sd_##IDX);                          \
                                                                               \
  for (size_t i = 0; i < MEM_DATA_SIZE; i++) {                                 \
    sd_##IDX[i].c = 0;                                                         \
    sd_##IDX[i].d = ((uint32_t *)emulator->slave[IDX]->r_data())[i];           \
  }                                                                            \
  vc_put4stVector(mem_##IDX##_r_bits_data, sd_##IDX);

    MEMORY_CHANNEL_PROP(0)
#ifdef MEM_HAS_CHANNEL1
    MEMORY_CHANNEL_PROP(1)
#endif
#ifdef MEM_HAS_CHANNEL2
    MEMORY_CHANNEL_PROP(2)
#endif
#ifdef MEM_HAS_CHANNEL3
    MEMORY_CHANNEL_PROP(3)
#endif

    vc_putScalar(reset, emulator->vcs_rst);
    vc_putScalar(fin, emulator->vcs_fin);

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
