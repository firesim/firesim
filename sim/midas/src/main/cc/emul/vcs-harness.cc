#include "simif_emul.h"
#include <DirectC.h>
#include <cassert>
#include <cmath>
#include <exception>
#include <fesvr/context.h>
#include <stdio.h>

extern context_t *host;
extern bool vcs_fin;
extern bool vcs_rst;
extern uint64_t main_time;

static const size_t CTRL_DATA_SIZE = CTRL_BEAT_BYTES / sizeof(uint32_t);
static const size_t DMA_DATA_SIZE = DMA_BEAT_BYTES / sizeof(uint32_t);
static const size_t DMA_STRB_SIZE =
    (DMA_BEAT_BYTES / 8 + sizeof(uint32_t) - 1) / sizeof(uint32_t);
static const size_t MEM_DATA_SIZE = MEM_BEAT_BYTES / sizeof(uint32_t);
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

          vc_handle dma_ar_valid,
          vc_handle dma_ar_ready,
          vc_handle dma_ar_bits_addr,
          vc_handle dma_ar_bits_id,
          vc_handle dma_ar_bits_size,
          vc_handle dma_ar_bits_len,

          vc_handle dma_aw_valid,
          vc_handle dma_aw_ready,
          vc_handle dma_aw_bits_addr,
          vc_handle dma_aw_bits_id,
          vc_handle dma_aw_bits_size,
          vc_handle dma_aw_bits_len,

          vc_handle dma_w_valid,
          vc_handle dma_w_ready,
          vc_handle dma_w_bits_strb,
          vc_handle dma_w_bits_data,
          vc_handle dma_w_bits_last,

          vc_handle dma_r_valid,
          vc_handle dma_r_ready,
          vc_handle dma_r_bits_resp,
          vc_handle dma_r_bits_id,
          vc_handle dma_r_bits_data,
          vc_handle dma_r_bits_last,

          vc_handle dma_b_valid,
          vc_handle dma_b_ready,
          vc_handle dma_b_bits_resp,
          vc_handle dma_b_bits_id,

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
    assert(simif_emul_t::dma != nullptr);
    assert(simif_emul_t::master != nullptr);

    assert(DMA_STRB_SIZE <= 2);

    uint32_t ctrl_r_data[CTRL_DATA_SIZE];
    for (size_t i = 0; i < CTRL_DATA_SIZE; i++) {
      ctrl_r_data[i] = vc_4stVectorRef(ctrl_r_bits_data)[i].d;
    }

    uint32_t dma_r_data[DMA_DATA_SIZE];
    for (size_t i = 0; i < DMA_DATA_SIZE; i++) {
      dma_r_data[i] = vc_4stVectorRef(dma_r_bits_data)[i].d;
    }

    simif_emul_t::master->tick(vcs_rst,
                               vc_getScalar(ctrl_ar_ready),
                               vc_getScalar(ctrl_aw_ready),
                               vc_getScalar(ctrl_w_ready),
                               vc_4stVectorRef(ctrl_r_bits_id)->d,
                               ctrl_r_data,
                               vc_getScalar(ctrl_r_bits_last),
                               vc_getScalar(ctrl_r_valid),
                               vc_4stVectorRef(ctrl_b_bits_id)->d,
                               vc_getScalar(ctrl_b_valid));

    simif_emul_t::dma->tick(vcs_rst,
                            vc_getScalar(dma_ar_ready),
                            vc_getScalar(dma_aw_ready),
                            vc_getScalar(dma_w_ready),
                            vc_4stVectorRef(dma_r_bits_id)->d,
                            dma_r_data,
                            vc_getScalar(dma_r_bits_last),
                            vc_getScalar(dma_r_valid),
                            vc_4stVectorRef(dma_b_bits_id)->d,
                            vc_getScalar(dma_b_valid));

#define MEMORY_CHANNEL_TICK(IDX)                                               \
  uint32_t mem_##IDX##_w_data[MEM_DATA_SIZE];                                  \
  for (size_t i = 0; i < MEM_DATA_SIZE; i++) {                                 \
    mem_##IDX##_w_data[i] = vc_4stVectorRef(mem_##IDX##_w_bits_data)[i].d;     \
  }                                                                            \
                                                                               \
  simif_emul_t::slave[IDX]->tick(vcs_rst,                                      \
                                 vc_getScalar(mem_##IDX##_ar_valid),           \
                                 vc_4stVectorRef(mem_##IDX##_ar_bits_addr)->d, \
                                 vc_4stVectorRef(mem_##IDX##_ar_bits_id)->d,   \
                                 vc_4stVectorRef(mem_##IDX##_ar_bits_size)->d, \
                                 vc_4stVectorRef(mem_##IDX##_ar_bits_len)->d,  \
                                                                               \
                                 vc_getScalar(mem_##IDX##_aw_valid),           \
                                 vc_4stVectorRef(mem_##IDX##_aw_bits_addr)->d, \
                                 vc_4stVectorRef(mem_##IDX##_aw_bits_id)->d,   \
                                 vc_4stVectorRef(mem_##IDX##_aw_bits_size)->d, \
                                 vc_4stVectorRef(mem_##IDX##_aw_bits_len)->d,  \
                                                                               \
                                 vc_getScalar(mem_##IDX##_w_valid),            \
                                 vc_4stVectorRef(mem_##IDX##_w_bits_strb)->d,  \
                                 mem_##IDX##_w_data,                           \
                                 vc_getScalar(mem_##IDX##_w_bits_last),        \
                                                                               \
                                 vc_getScalar(mem_##IDX##_r_ready),            \
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

    if (!vcs_fin)
      host->switch_to();
    else
      vcs_fin = false;

    vc_putScalar(ctrl_aw_valid, simif_emul_t::master->aw_valid());
    vc_putScalar(ctrl_ar_valid, simif_emul_t::master->ar_valid());
    vc_putScalar(ctrl_w_valid, simif_emul_t::master->w_valid());
    vc_putScalar(ctrl_w_bits_last, simif_emul_t::master->w_last());
    vc_putScalar(ctrl_r_ready, simif_emul_t::master->r_ready());
    vc_putScalar(ctrl_b_ready, simif_emul_t::master->b_ready());

    vec32 md[CTRL_DATA_SIZE];
    md[0].c = 0;
    md[0].d = simif_emul_t::master->aw_id();
    vc_put4stVector(ctrl_aw_bits_id, md);
    md[0].c = 0;
    md[0].d = simif_emul_t::master->aw_addr();
    vc_put4stVector(ctrl_aw_bits_addr, md);
    md[0].c = 0;
    md[0].d = simif_emul_t::master->aw_size();
    vc_put4stVector(ctrl_aw_bits_size, md);
    md[0].c = 0;
    md[0].d = simif_emul_t::master->aw_len();
    vc_put4stVector(ctrl_aw_bits_len, md);
    md[0].c = 0;
    md[0].d = simif_emul_t::master->ar_id();
    vc_put4stVector(ctrl_ar_bits_id, md);
    md[0].c = 0;
    md[0].d = simif_emul_t::master->ar_addr();
    vc_put4stVector(ctrl_ar_bits_addr, md);
    md[0].c = 0;
    md[0].d = simif_emul_t::master->ar_size();
    vc_put4stVector(ctrl_ar_bits_size, md);
    md[0].c = 0;
    md[0].d = simif_emul_t::master->ar_len();
    vc_put4stVector(ctrl_ar_bits_len, md);
    md[0].c = 0;
    md[0].d = simif_emul_t::master->w_strb();
    vc_put4stVector(ctrl_w_bits_strb, md);

    for (size_t i = 0; i < CTRL_DATA_SIZE; i++) {
      md[i].c = 0;
      md[i].d = ((uint32_t *)simif_emul_t::master->w_data())[i];
    }
    vc_put4stVector(ctrl_w_bits_data, md);

    vc_putScalar(dma_aw_valid, simif_emul_t::dma->aw_valid());
    vc_putScalar(dma_ar_valid, simif_emul_t::dma->ar_valid());
    vc_putScalar(dma_w_valid, simif_emul_t::dma->w_valid());
    vc_putScalar(dma_w_bits_last, simif_emul_t::dma->w_last());
    vc_putScalar(dma_r_ready, simif_emul_t::dma->r_ready());
    vc_putScalar(dma_b_ready, simif_emul_t::dma->b_ready());

    vec32 dd[DMA_DATA_SIZE];
    dd[0].c = 0;
    dd[0].d = simif_emul_t::dma->aw_id();
    vc_put4stVector(dma_aw_bits_id, dd);
    dd[0].c = 0;
    dd[0].d = simif_emul_t::dma->aw_addr();
    dd[1].c = 0;
    dd[1].d = simif_emul_t::dma->aw_addr() >> 32;
    vc_put4stVector(dma_aw_bits_addr, dd);
    dd[0].c = 0;
    dd[0].d = simif_emul_t::dma->aw_size();
    vc_put4stVector(dma_aw_bits_size, dd);
    dd[0].c = 0;
    dd[0].d = simif_emul_t::dma->aw_len();
    vc_put4stVector(dma_aw_bits_len, dd);
    dd[0].c = 0;
    dd[0].d = simif_emul_t::dma->ar_id();
    vc_put4stVector(dma_ar_bits_id, dd);
    dd[0].c = 0;
    dd[0].d = simif_emul_t::dma->ar_addr();
    dd[1].c = 0;
    dd[1].d = simif_emul_t::dma->ar_addr() >> 32;
    vc_put4stVector(dma_ar_bits_addr, dd);
    dd[0].c = 0;
    dd[0].d = simif_emul_t::dma->ar_size();
    vc_put4stVector(dma_ar_bits_size, dd);
    dd[0].c = 0;
    dd[0].d = simif_emul_t::dma->ar_len();
    vc_put4stVector(dma_ar_bits_len, dd);

    auto strb = simif_emul_t::dma->w_strb();
    for (size_t i = 0; i < DMA_STRB_SIZE; i++) {
      dd[i].c = 0;
      dd[i].d = ((uint32_t *)(&strb))[i];
    }
    vc_put4stVector(dma_w_bits_strb, dd);

    for (size_t i = 0; i < DMA_DATA_SIZE; i++) {
      dd[i].c = 0;
      dd[i].d = ((uint32_t *)simif_emul_t::dma->w_data())[i];
    }
    vc_put4stVector(dma_w_bits_data, dd);

#define MEMORY_CHANNEL_PROP(IDX)                                               \
  vc_putScalar(mem_##IDX##_aw_ready, simif_emul_t::slave[IDX]->aw_ready());    \
  vc_putScalar(mem_##IDX##_ar_ready, simif_emul_t::slave[IDX]->ar_ready());    \
  vc_putScalar(mem_##IDX##_w_ready, simif_emul_t::slave[IDX]->w_ready());      \
  vc_putScalar(mem_##IDX##_b_valid, simif_emul_t::slave[IDX]->b_valid());      \
  vc_putScalar(mem_##IDX##_r_valid, simif_emul_t::slave[IDX]->r_valid());      \
  vc_putScalar(mem_##IDX##_r_bits_last, simif_emul_t::slave[IDX]->r_last());   \
                                                                               \
  vec32 sd_##IDX[MEM_DATA_SIZE];                                               \
  sd_##IDX[0].c = 0;                                                           \
  sd_##IDX[0].d = simif_emul_t::slave[IDX]->b_id();                            \
  vc_put4stVector(mem_##IDX##_b_bits_id, sd_##IDX);                            \
  sd_##IDX[0].c = 0;                                                           \
  sd_##IDX[0].d = simif_emul_t::slave[IDX]->b_resp();                          \
  vc_put4stVector(mem_##IDX##_b_bits_resp, sd_##IDX);                          \
  sd_##IDX[0].c = 0;                                                           \
  sd_##IDX[0].d = simif_emul_t::slave[IDX]->r_id();                            \
  vc_put4stVector(mem_##IDX##_r_bits_id, sd_##IDX);                            \
  sd_##IDX[0].c = 0;                                                           \
  sd_##IDX[0].d = simif_emul_t::slave[IDX]->r_resp();                          \
  vc_put4stVector(mem_##IDX##_r_bits_resp, sd_##IDX);                          \
                                                                               \
  for (size_t i = 0; i < MEM_DATA_SIZE; i++) {                                 \
    sd_##IDX[i].c = 0;                                                         \
    sd_##IDX[i].d = ((uint32_t *)simif_emul_t::slave[IDX]->r_data())[i];       \
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

    vc_putScalar(reset, vcs_rst);
    vc_putScalar(fin, vcs_fin);

    main_time++;
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
