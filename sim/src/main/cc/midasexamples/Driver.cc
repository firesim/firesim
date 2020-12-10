//See LICENSE for license details.

#ifndef RTLSIM
#include "simif_f1.h"
#else
#include "simif_emul.h"
#endif

// This is heinous i'm sorry...
#ifdef DESIGNNAME_EnableShiftRegister
#include "EnableShiftRegister.h"
#elif defined DESIGNNAME_GCD
#include "GCD.h"
#elif defined DESIGNNAME_Parity
#include "Parity.h"
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
#elif defined DESIGNNAME_PrintfModule
#include "PrintfModule.h"
#elif defined DESIGNNAME_NarrowPrintfModule
#include "NarrowPrintfModule.h"
#elif defined DESIGNNAME_MulticlockPrintfModule
#include "MulticlockPrintfModule.h"
#elif defined DESIGNNAME_AutoCounterModule
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
#elif defined DESIGNNAME_MultiSRAM
#include "MultiSRAM.h"
#elif defined DESIGNNAME_NestedModels
#include "NestedModels.h"
#elif defined DESIGNNAME_MultiReg
#include "MultiReg.h"
#endif

class dut_emul_t:
#ifdef RTLSIM
  public simif_emul_t,
#else
  public simif_f1_t,
#endif
  public DESIGNDRIVERCLASS
{
public:
#ifdef RTLSIM
  dut_emul_t(int argc, char** argv):
    DESIGNDRIVERCLASS(argc, argv) { }
#else
  dut_emul_t(int argc, char** argv): simif_f1_t(argc, argv), DESIGNDRIVERCLASS(argc, argv) { }
#endif

};

int main(int argc, char** argv)
{
  dut_emul_t dut(argc, argv);
  dut.init(argc, argv, true);
  dut.run();
  return dut.finish();
}
