// See LICENSE for license details.

package firesim.lib.bridges

import chisel3._

import firesim.lib.nasti.{NastiIO, NastiParameters}
import firesim.lib.bridgeutils._

// Copied from Rocket Chip. Simplified.
case class AddressSet(base: BigInt, mask: BigInt)

// A serializable summary of the diplomatic edge. Copied from FireSim. Simplified.
case class AXI4EdgeSummary(
  maxReadTransfer:  Int,
  maxWriteTransfer: Int,
  idReuse:          Option[Int],
  maxFlight:        Option[Int],
  address:          Seq[AddressSet],
)

// Need to wrap up all the parameters for the FASEDBridge in a case class for serialization.
case class CompleteConfig(
  axi4Widths:       NastiParameters,
  axi4Edge:         Option[AXI4EdgeSummary] = None,
  memoryRegionName: Option[String]          = None,
)

class FASEDTargetIO(nastiParams: NastiParameters) extends Bundle {
  val axi4  = Flipped(new NastiIO(nastiParams))
  val reset = Input(Bool())
  val clock = Input(Clock())
}

class FASEDBridge(argument: CompleteConfig) extends BlackBox with Bridge[HostPortIO[FASEDTargetIO]] {
  val moduleName     = "midas.models.FASEDMemoryTimingModel"
  val io             = IO(new FASEDTargetIO(argument.axi4Widths))
  val bridgeIO       = HostPort(io)
  val constructorArg = Some(argument)
  generateAnnotations()
}

object FASEDBridge {
  def apply(clock: Clock, axi4: NastiIO, reset: Bool, cfg: CompleteConfig): FASEDBridge = {
    val ep = Module(new FASEDBridge(cfg))
    ep.io.reset := reset
    ep.io.clock := clock
    ep.io.axi4  <> axi4
    ep
  }
}
