// See LICENSE for license details.

package midas
package widgets


import chisel3._
import org.chipsalliance.cde.config.Parameters

class SimulationMasterIO(implicit val p: Parameters) extends WidgetIO()(p){
}

class SimulationMaster(implicit p: Parameters) extends Widget()(p) {
  lazy val module = new WidgetImp(this) {
    val io = IO(new SimulationMasterIO)

    val initDelay = RegInit(64.U)
    when (initDelay =/= 0.U) { initDelay := initDelay - 1.U }
    genRORegInit(initDelay === 0.U, "INIT_DONE", 0.U)

    // add fingerprint to see if device is FireSim-enabled
    val fingerprint = 0x46697265
    val rFingerprint = RegInit(fingerprint.U(32.W))
    genROReg(rFingerprint, "PRESENCE_READ")

    val wFingerprint = genWORegInit(Wire(UInt(32.W)), "PRESENCE_WRITE", fingerprint.U(32.W))
    when (wFingerprint =/= rFingerprint) {
      rFingerprint := wFingerprint
    }

    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      genConstructor(
          base,
          sb,
          "master_t",
          "master",
          Seq(),
          "GET_CORE_CONSTRUCTOR"
      )
    }
  }
}
