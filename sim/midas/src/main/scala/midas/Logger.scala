package midas

import chisel3._

object PrintfLogger {
  def logInfo(format: String, args: Bits*): Unit = {
    val loginfo_cycles = RegInit(0.U(64.W))
    loginfo_cycles := loginfo_cycles + 1.U

    printf("cy: %d, ", loginfo_cycles)
    printf(Printable.pack(format, args: _*))
  }
}
