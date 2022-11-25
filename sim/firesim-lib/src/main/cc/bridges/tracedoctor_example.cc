#include "tracedoctor_example.h"

/*
 * This is an example how to implement a TracerV like trace with TraceDoctor
 * This example does not cover fireperf (though including it here is easy)
 * and it traces all instructions at commit with a valid bit vector
*/

tracedoctor_tracerv::tracedoctor_tracerv(std::vector<std::string> const args, struct traceInfo const info)
    : tracedoctor_worker("TracerV", args, info, 1), binary(false)
{
  for (auto &a: args) {
    if (a == "binary") {
      binary = true;
      continue;
    }
  }

  if (!binary) {
    fprintf(std::get<freg_descriptor>(fileRegister[0]), "cycle;instr0;instr1;instr2;instr3;instr4;instr5\n");
  }

  fprintf(stdout, "%s: file(%s), binary(%d)\n", tracerName.c_str(), std::get<freg_name>(fileRegister[0]).c_str(), binary);
}

tracedoctor_tracerv::~tracedoctor_tracerv() {
    fprintf(stdout, "%s: file(%s)\n", tracerName.c_str(), std::get<freg_name>(fileRegister[0]).c_str());
}

void tracedoctor_tracerv::tick(char const * const data, unsigned int tokens) {
  if (binary) {
    fwrite(data, 1, tokens * info.tokenBytes, std::get<freg_descriptor>(fileRegister[0]));
  } else {
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
  }
}