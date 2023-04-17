#include "tracedoctor_example.h"

/*
 * This is an example how to implement a TracerV like trace with TraceDoctor
*/

tracedoctor_tracerv::tracedoctor_tracerv(std::vector<std::string> const &args, struct traceInfo const &info)
    : tracedoctor_worker("TracerV", args, info, 1), mode(TRACERV_CSV), tracerv_tracker(NULL)
{
  std::string dwarfFileName = "";

  for (auto &a: args) {
    if (a == "csv") {
      mode = TRACERV_CSV;
    } else if (a == "binary") {
      mode = TRACERV_BINARY;
    } else if (a == "fireperf") {
      mode = TRACERV_FIREPERF;
    } else if (a.rfind("dwarf-file-name", 0) == 0 && a.find(":") != std::string::npos) {
      dwarfFileName = a.substr(a.find(":") + 1);
    }
  }

  if (mode == TRACERV_CSV) {
    fprintf(std::get<freg_descriptor>(fileRegister[0]), "cycle;instr0;instr1;instr2;instr3;instr4;instr5\n");
  } else if (mode == TRACERV_FIREPERF) {
    if (dwarfFileName.empty()) {
      throw std::invalid_argument("No dwarf-file-name given to the fireperf option! Specify options like 'fireperf,dwarf-file-name:bin.elf'");
    }
    tracerv_tracker = new TraceTracker(dwarfFileName, std::get<freg_descriptor>(fileRegister[0]));
  }

  fprintf(stdout, "%s: file(%s), csv(%d), binary(%d), fireperf(%d)\n",
          tracerName.c_str(),
          std::get<freg_name>(fileRegister[0]).c_str(),
          mode == TRACERV_CSV,
          mode == TRACERV_BINARY,
          mode == TRACERV_FIREPERF);
}

tracedoctor_tracerv::~tracedoctor_tracerv() {
  if (tracerv_tracker) {
    delete tracerv_tracker;
  }
}

void tracedoctor_tracerv::tick(char const * const data, unsigned int tokens) {
  if (mode == TRACERV_CSV) {
    struct traceLayout const * const traceTokens = (struct traceLayout const * const) data;
    for (unsigned int i = 0; i < tokens; i++) {
      struct traceLayout const traceToken = traceTokens[i];
      fprintf(std::get<freg_descriptor>(fileRegister[0]), "%lu;0x%lx;0x%lx;0x%lx;0x%lx;0x%lx;0x%lx\n",
              traceToken.timestamp,
              (traceToken.valids & 0b000001) ? traceToken.instr0 : 0,
              (traceToken.valids & 0b000010) ? traceToken.instr1 : 0,
              (traceToken.valids & 0b000100) ? traceToken.instr2 : 0,
              (traceToken.valids & 0b001000) ? traceToken.instr3 : 0,
              (traceToken.valids & 0b010000) ? traceToken.instr4 : 0,
              (traceToken.valids & 0b100000) ? traceToken.instr5 : 0
      );
    }
  } else if (mode == TRACERV_BINARY) {
    fwrite(data, 1, tokens * info.tokenBytes, std::get<freg_descriptor>(fileRegister[0]));
  } else {
    struct traceLayout const * const traceTokens = (struct traceLayout const * const) data;
    for (unsigned int i = 0; i < tokens; i++) {
      struct traceLayout const traceToken = traceTokens[i];
      if (traceToken.valids & 0b000001)
        tracerv_tracker->addInstruction(traceToken.instr0, traceToken.timestamp);
      if (traceToken.valids & 0b000010)
        tracerv_tracker->addInstruction(traceToken.instr1, traceToken.timestamp);
      if (traceToken.valids & 0b000100)
        tracerv_tracker->addInstruction(traceToken.instr2, traceToken.timestamp);
      if (traceToken.valids & 0b001000)
        tracerv_tracker->addInstruction(traceToken.instr3, traceToken.timestamp);
      if (traceToken.valids & 0b010000)
        tracerv_tracker->addInstruction(traceToken.instr4, traceToken.timestamp);
      if (traceToken.valids & 0b100000)
        tracerv_tracker->addInstruction(traceToken.instr5, traceToken.timestamp);
    }
  }
}