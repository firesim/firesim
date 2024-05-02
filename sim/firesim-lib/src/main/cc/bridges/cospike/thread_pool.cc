#include <iostream>
#include <fstream>
#include <inttypes.h>
#include "thread_pool.h"

void print_insn_logs(trace_t trace, std::string oname) {
  std::ofstream os(oname, std::ofstream::out);
  trace_cfg_t& cfg = trace.cfg;
  uint8_t* buf = trace.buf;

  const size_t bytes_per_trace = cfg._bits_per_trace / 8;

  for (uint32_t offset = 0; offset < trace.sz; offset += bytes_per_trace) {
    uint8_t* cur_buf = buf + offset;
    uint64_t time = EXTRACT_ALIGNED(
        int64_t, uint64_t, cur_buf, cfg._time_width, cfg._time_offset);
    bool valid = cur_buf[cfg._valid_offset];
    // this crazy to extract the right value then sign extend within the size
    uint64_t iaddr = EXTRACT_ALIGNED(int64_t,
                                     uint64_t,
                                     cur_buf,
                                     cfg._iaddr_width,
                                     cfg._iaddr_offset); // aka the pc
    uint32_t insn = EXTRACT_ALIGNED(
        int32_t, uint32_t, cur_buf, cfg._insn_width, cfg._insn_offset);
    bool exception = cur_buf[cfg._exception_offset];
    bool interrupt = cur_buf[cfg._interrupt_offset];
    uint64_t cause = EXTRACT_ALIGNED(
        int64_t, uint64_t, cur_buf, cfg._cause_width, cfg._cause_offset);
    bool has_w = cfg._wdata_width != 0;
    uint64_t wdata =
        cfg._wdata_width != 0
            ? EXTRACT_ALIGNED(
                  int64_t, uint64_t, cur_buf, cfg._wdata_width, cfg._wdata_offset)
            : 0;
    uint8_t priv = cur_buf[cfg._priv_offset];

    os << std::dec << valid << " " <<
                      exception << " " <<
                      interrupt << " " <<
                      has_w << " " <<
                      (int)cause << " " <<
                      time << " " <<
          std::hex << iaddr << " " <<
                      wdata << "\n";
  }
  os.close();
}
