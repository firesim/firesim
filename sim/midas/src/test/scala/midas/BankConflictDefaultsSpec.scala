// See LICENSE for license details.

package midas.tests

import chisel3.RawModule
import firesim.lib.nasti.NastiParameters
import junctions.NastiKey
import midas.models._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec

class BankConflictDefaultsSpec extends AnyFlatSpec {
  behavior.of("BankConflictModel runtime configuration defaults")

  implicit val parameters: Parameters = Parameters.empty.alterPartial { case NastiKey => NastiParameters(64, 32, 2) }

  for (banks <- Seq(2, 4, 8, 16, 32)) {
    it should s"export a representable mask that enables every bank in a $banks-bank model" in {
      midas.ElaborateChiselSubCircuit(new RawModule {
        val registers    = new BankConflictMMRegIO(BankConflictConfig(banks, params = BaseParams(1, 1)))
        val defaults     = registers.getDefaults().toMap
        val offset       = defaults("bankAddr_offset")
        val mask         = defaults("bankAddr_mask")
        val maskRegister = registers.bankAddr.regMap(registers.bankAddr.mask)

        // The same defaults are emitted as +mm_* settings and used for MMIO initialization.
        // They must satisfy the runtime setting's advertised range and fit the hardware.
        assert(
          mask >= maskRegister.min && maskRegister.max.forall(mask <= _),
          s"mask $mask is outside the runtime setting range",
        )
        assert(
          mask.bitLength <= registers.bankAddr.mask.getWidth,
          s"mask $mask would be truncated by the MMIO register",
        )

        val observedBanks = (0 until banks).map { bank =>
          val address = BigInt(bank) << offset.toInt
          ((address >> offset.toInt) & mask).toInt
        }
        assert(observedBanks == (0 until banks), s"default configuration aliases banks: $observedBanks")
        println(
          s"banks=$banks default bankAddr_offset=$offset bankAddr_mask=$mask width=${registers.bankAddr.mask.getWidth}; all banks reachable"
        )
      })
    }
  }
}
