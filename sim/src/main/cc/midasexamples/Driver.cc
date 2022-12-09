// See LICENSE for license details.

#ifndef RTLSIM
#include "simif_f1.h"
#define SIMIF simif_f1_t
#else
#include "simif_emul.h"
#define SIMIF simif_emul_t
#endif

// This is heinous i'm sorry...
#ifdef DESIGNNAME_EnableShiftRegister
#include "EnableShiftRegister.h"
#elif defined DESIGNNAME_GCD
#include "GCD.h"
#elif defined DESIGNNAME_Parity
#include "Parity.h"
#elif defined DESIGNNAME_PlusArgsModule
#include "PlusArgsModule.h"
#elif defined DESIGNNAME_PointerChaser
#include "PointerChaser.h"
#elif defined DESIGNNAME_ResetShiftRegister
#include "ResetShiftRegister.h"
#elif defined DESIGNNAME_Risc
#include "Risc.h"
#elif defined DESIGNNAME_RiscSRAM
#include "RiscSRAM.h"
#elif defined DESIGNNAME_ShiftRegister
#include "ShiftRegister.h"
#elif defined DESIGNNAME_Stack
#include "Stack.h"
#elif defined DESIGNNAME_WireInterconnect
#include "WireInterconnect.h"
#elif defined DESIGNNAME_AssertModule
#include "AssertModule.h"
#elif defined DESIGNNAME_AssertGlobalResetCondition
#include "AssertTorture.h"
#elif defined DESIGNNAME_PrintfModule
#include "PrintfModule.h"
#elif defined DESIGNNAME_NarrowPrintfModule
#include "NarrowPrintfModule.h"
#elif defined DESIGNNAME_MulticlockPrintfModule
#include "MulticlockPrintfModule.h"
#elif defined DESIGNNAME_TriggerPredicatedPrintf
#include "PrintfModule.h"
#elif defined DESIGNNAME_PrintfGlobalResetCondition
#include "PrintfModule.h"
#elif defined DESIGNNAME_AutoCounterModule
#include "AutoCounterModule.h"
#elif defined DESIGNNAME_AutoCounter32bRollover
#include "AutoCounterModule.h"
#elif defined DESIGNNAME_AutoCounterGlobalResetCondition
#include "AutoCounterModule.h"
#elif defined DESIGNNAME_AutoCounterCoverModule
#include "AutoCounterCoverModule.h"
#elif defined DESIGNNAME_AutoCounterPrintfModule
#include "PrintfModule.h"
#elif defined DESIGNNAME_MulticlockAutoCounterModule
#include "MulticlockAutoCounterModule.h"
#elif defined DESIGNNAME_Accumulator
#include "Accumulator.h"
#elif defined DESIGNNAME_VerilogAccumulator
#include "VerilogAccumulator.h"
#elif defined DESIGNNAME_TrivialMulticlock
#include "TrivialMulticlock.h"
#elif defined DESIGNNAME_MulticlockAssertModule
#include "MulticlockAssertModule.h"
#elif defined DESIGNNAME_AssertTorture
#include "AssertTorture.h"
#elif defined DESIGNNAME_TriggerWiringModule
#include "TriggerWiringModule.h"
#elif defined DESIGNNAME_TwoAdders
#include "TwoAdders.h"
#elif defined DESIGNNAME_Regfile
#include "Regfile.h"
#elif defined DESIGNNAME_MultiRegfile
#include "MultiRegfile.h"
#elif defined DESIGNNAME_MultiRegfileFMR
#include "MultiRegfileFMR.h"
#elif defined DESIGNNAME_MultiSRAM
#include "MultiSRAM.h"
#elif defined DESIGNNAME_MultiSRAMFMR
#include "MultiSRAMFMR.h"
#elif defined DESIGNNAME_NestedModels
#include "NestedModels.h"
#elif defined DESIGNNAME_MultiReg
#include "MultiReg.h"
#elif defined DESIGNNAME_PassthroughModel
#include "PassthroughModels.h"
#elif defined DESIGNNAME_PassthroughModelNested
#include "PassthroughModels.h"
#elif defined DESIGNNAME_PassthroughModelBridgeSource
#include "PassthroughModels.h"
#elif defined DESIGNNAME_ResetPulseBridgeTest
#include "ResetPulseBridgeTest.h"
#elif defined DESIGNNAME_TerminationModule
#include "TerminationModule.h"
#elif defined DESIGNNAME_CustomConstraints
#include "CustomConstraints.h"
#endif

class dut_emul_t : public SIMIF, public DESIGNDRIVERCLASS {
public:
  dut_emul_t(const std::vector<std::string> &args)
      : SIMIF(args), DESIGNDRIVERCLASS(args, this) {}
};

int main(int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  dut_emul_t dut(args);
  dut.init(argc, argv);
  dut.run();
  return dut.teardown();
}
