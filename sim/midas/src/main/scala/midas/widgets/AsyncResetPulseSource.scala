// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest.{UnitTest}


class AsyncResetPulseSource extends RawModule {
  val out = IO(Output(AsyncReset()))
  out := false.B.asAsyncReset
}

object AsyncResetPulseSource {
  def apply(): AsyncReset = Module(new AsyncResetPulseSource()).out
}

