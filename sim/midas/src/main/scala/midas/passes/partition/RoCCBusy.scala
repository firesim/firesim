package midas.passes.partition

import scala.Console.println
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import midas.targetutils.RoCCBusyFirrtlAnnotation

/*
 * This is basically a hack for gemmini and sha3
 *  val busy_reg = RegInit(false.B)
 *  val prev_busy_reg = Reg(Bool())
 *  prev_busy_reg := partition.io_busy
 *
 *  when (!busy_reg && io.cmd.fire) {
 *    busy_reg := true.B
 *  } .elsewhen (busy_reg && prev_busy_reg && !partition.io_busy) {
 *    busy_reg := false.B
 *  } .else {
 *    busy_reg := busy_reg
 *  }
 *
 *  io.busy := busy_reg || cmdFire || partition.io_busy
 */

// Assumes that InsertWrapperPass already ran
trait MakeRoCCBusyBitLatencyInsensitivePass {
  def replaceWrapperBody(busyReadyValid: (String, String, String), idx: Int)(stmt: Statement): Statement = {
    val busy  = busyReadyValid._1
    val ready = busyReadyValid._2
    val valid = busyReadyValid._3

    stmt match {
      case Connect(_, WRef(io_x), WSubField(WRef(inst), x, _, _)) if (io_x._1 == busy) =>
        val cmdFire = DefNode(
          NoInfo,
          s"rocc_fire_${idx}",
          DoPrim(PrimOps.And, Seq(WRef(valid), WRef(ready)), Seq(), firrtl.ir.UIntType(IntWidth(1))),
        )

        val busyReg = DefRegister(
          NoInfo,
          s"busy_reg_${idx}",
          firrtl.ir.UIntType(IntWidth(1)),
          WRef("clock"),
          WRef("reset"),
          UIntLiteral(0, IntWidth(1)),
        )

        val prevBusyReg = DefRegister(
          NoInfo,
          s"prev_busy_reg_${idx}",
          firrtl.ir.UIntType(IntWidth(1)),
          WRef("clock"),
          WRef("reset"),
          UIntLiteral(0, IntWidth(1)),
        )

        val updatePrevBusy = Connect(
          NoInfo,
          WRef(prevBusyReg),
          WSubField(WRef(inst._1), x),
        )

        val notBusyReg = DefNode(
          NoInfo,
          s"not_busy_reg_${idx}",
          DoPrim(PrimOps.Not, Seq(WRef(busyReg)), Seq(), firrtl.ir.UIntType(IntWidth(1))),
        )

        val updateBusyRegWhenLow = DefNode(
          NoInfo,
          s"raise_busy_${idx}",
          DoPrim(PrimOps.And, Seq(WRef(cmdFire), WRef(notBusyReg)), Seq(), firrtl.ir.UIntType(IntWidth(1))),
        )

        val notPartitionBusy = DefNode(
          NoInfo,
          s"not_part_busy_${idx}",
          DoPrim(PrimOps.Not, Seq(WSubField(WRef(inst._1), x)), Seq(), firrtl.ir.UIntType(IntWidth(1))),
        )

        val prevBusyAndCurrentNotBusy = DefNode(
          NoInfo,
          s"lower_busy_1_${idx}",
          DoPrim(PrimOps.And, Seq(WRef(prevBusyReg), WRef(notPartitionBusy)), Seq(), firrtl.ir.UIntType(IntWidth(1))),
        )

        val lowerBusyRegCond = DefNode(
          NoInfo,
          s"lower_busy_${idx}",
          DoPrim(
            PrimOps.And,
            Seq(WRef(busyReg), WRef(prevBusyAndCurrentNotBusy)),
            Seq(),
            firrtl.ir.UIntType(IntWidth(1)),
          ),
        )

        val updateBusyRegWhenHigh = DefNode(
          NoInfo,
          s"busy_reg_next_when_high_${idx}",
          firrtl.ir.Mux(
            WRef(lowerBusyRegCond),
            UIntLiteral(0, IntWidth(1)),
            WRef(busyReg),
            firrtl.ir.UIntType(IntWidth(1)),
          ),
        )

        val busyRegNext = DefNode(
          NoInfo,
          s"busy_reg_next_${idx}",
          firrtl.ir.Mux(
            WRef(updateBusyRegWhenLow),
            UIntLiteral(1, IntWidth(1)),
            WRef(updateBusyRegWhenHigh),
            firrtl.ir.UIntType(IntWidth(1)),
          ),
        )

        val updateBusyReg = Connect(NoInfo, WRef(busyReg), WRef(busyRegNext))

        val busyRegOrCmdFire = DefNode(
          NoInfo,
          s"busy_or_fire_${idx}",
          DoPrim(PrimOps.Or, Seq(WRef(cmdFire), WRef(busyReg)), Seq(), firrtl.ir.UIntType(IntWidth(1))),
        )

        val busyRegOrCmdFireOrIncomingBusy = DefNode(
          NoInfo,
          s"busy_or_fire_or_incoming_busy_${idx}",
          DoPrim(
            PrimOps.Or,
            Seq(WRef(busyRegOrCmdFire), WSubField(WRef(inst._1), x)),
            Seq(),
            firrtl.ir.UIntType(IntWidth(1)),
          ),
        )

        val connIOBusy = Connect(NoInfo, WRef(io_x._1), WRef(busyRegOrCmdFireOrIncomingBusy))

        Block(
          Seq(
            cmdFire,
            busyReg,
            prevBusyReg,
            updatePrevBusy,
            notBusyReg,
            updateBusyRegWhenLow,
            notPartitionBusy,
            prevBusyAndCurrentNotBusy,
            lowerBusyRegCond,
            updateBusyRegWhenHigh,
            busyRegNext,
            updateBusyReg,
            busyRegOrCmdFire,
            busyRegOrCmdFireOrIncomingBusy,
            connIOBusy,
          )
        )
      case s                                                                           => s
    }
  }

  def transformWrapper(
    wrapperModuleName: String,
    busyReadyValid:    (String, String, String),
    idx:               Int,
  )(m:                 DefModule
  ): DefModule = {
    m match {
      case wm: Module if (wm.name == wrapperModuleName) =>
        println(s"transformWrapper ${wrapperModuleName} ${busyReadyValid}")
        val replacedBody = wm.body.mapStmt(replaceWrapperBody(busyReadyValid, idx))
        wm.copy(body = replacedBody)
      case mod                                          => mod
    }
  }

  def replaceIncomingBusyWithRoCCFired(
    state:             CircuitState,
    wrapperModuleName: String,
  ): CircuitState = {

    val roccBusy = state.annotations.collect {
      case ann: RoCCBusyFirrtlAnnotation if (ann.target.module == wrapperModuleName) =>
        (ann.target.ref, ann.ready.ref, ann.valid.ref)
    }

    println(s"roccBusy ${roccBusy}")

    if (roccBusy.size == 0) {
      state
    } else {
      val circuit         = state.circuit
      val replacedCircuit = roccBusy.zipWithIndex.foldLeft(circuit)((cir, brvidx) =>
        cir.map(transformWrapper(wrapperModuleName, brvidx._1, brvidx._2))
      )
      println("- ReplaceIncomingBusyWithRoCCFired done")
      state.copy(circuit = replacedCircuit)
    }
  }
}
