#ifndef __TRACEDOCTOR_EXAMPLE_H_
#define __TRACEDOCTOR_EXAMPLE_H_

#include "tracedoctor_worker.h"
#include "tracerv/trace_tracker.h"

class tracedoctor_tracerv : public tracedoctor_worker {
private:
  struct traceLayout {
    uint64_t timestamp;
    uint64_t valids;
    uint64_t instr0;
    uint64_t instr1;
    uint64_t instr2;
    uint64_t instr3;
    uint64_t instr4;
    uint64_t instr5;
  };

  enum tracerv_mode {TRACERV_CSV, TRACERV_BINARY, TRACERV_FIREPERF} mode;
  TraceTracker *tracerv_tracker;
public:
  tracedoctor_tracerv(std::vector<std::string> const &args, struct traceInfo const &info);
  ~tracedoctor_tracerv() override;

  void tick(char const * const data, unsigned int tokens) override;
};

#endif // __TRACEDOCTOR_EXAMPLE_H_
