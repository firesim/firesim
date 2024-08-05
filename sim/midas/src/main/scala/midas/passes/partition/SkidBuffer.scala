package midas.passes.partition

import scala.collection.mutable
import scala.Console.println
import firrtl._
import firrtl.ir._
import firrtl.Mappers._

// NOTE: For now, lets assume that the decoupled is combinational. Need to come up with a method to detect combinatinal logic between partitions though.
// NOTE: Assumes that the flattend decoupled bundles are named as (pfx_valid, pfx_ready, pfx_bits_{a, b, c, d...})
// Assumes that InsertWrapperPass already ran
trait SkidBufferInsertionPass {
  private var idx              = 0
  private val skidBuffersToAdd = mutable.ArrayBuffer[DefModule]()
  private val queuesToAdd      = mutable.ArrayBuffer[DefModule]()

  private def UIntW(x: Int): firrtl.ir.UIntType = {
    firrtl.ir.UIntType(IntWidth(x))
  }

  private def getReadyValidSfx(name: String): String = {
    // Ready & valid are both 5 characters
    val len = name.length
    if (len < 5) name
    else name.substring(len - 5, len)
  }

  private def getReadyValidPfx(name: String): String = {
    val len = name.length
    if (len < 5) name
    else name.substring(0, len - 5)
  }

  private def collectDecoupledIO(
    ports:               Seq[Port],
    collectDir:          String,
    decoupledSignalsMap: mutable.Map[String, mutable.ArrayBuffer[WRef]],
  ): Unit = {
    def collectReadyValidPfx(
      validSignalPfx: mutable.Set[String],
      readySignalPfx: mutable.Set[String],
    )(name:           String,
      portDir:        String,
      collectDir:     String,
    ): Unit = {
      val rvsfx = getReadyValidSfx(name)
      if (rvsfx == "valid" && portDir == collectDir) {
        validSignalPfx.add(getReadyValidPfx(name))
      } else if (rvsfx == "ready") {
        readySignalPfx.add(getReadyValidPfx(name))
      } else {
        // do nothing
      }
    }

    def checkIfDecoupledSignal(pfxSet: mutable.Set[String], signal: WRef): Unit = {
      val signalName   = signal.name
      val decoupledPfx = pfxSet.toSeq.filter(p => signalName.contains(p))

      if (!decoupledPfx.isEmpty) {
        assert(decoupledPfx.length == 1, s"multiple decoupled pfx for this signal, PFX set ${pfxSet} signal ${signal}")

        val curPfx = decoupledPfx.head
        decoupledSignalsMap(curPfx).append(signal)
      }
    }

    val validSignalPfx = mutable.Set[String]()
    val readySignalPfx = mutable.Set[String]()
    ports.foreach { p =>
      collectReadyValidPfx(validSignalPfx, readySignalPfx)(p.name, p.direction.serialize, collectDir)
    }

    val decoupledPfx = validSignalPfx.intersect(readySignalPfx)
    decoupledPfx.toSeq.foreach { pfx =>
      decoupledSignalsMap(pfx) = new mutable.ArrayBuffer[WRef]()
    }

    ports.foreach { p =>
      checkIfDecoupledSignal(decoupledPfx, WRef(p.name, p.tpe))
    }
// println(s"decoupledSignalsMap ${decoupledSignalsMap}")
  }

  private def generateQueue(name: String, depth: Int, enq: Seq[Port]): DefModule = {
    import scala.math.log10
    val log2      = (x: Double) => log10(x) / log10(2.0)
    val depthLog2 = log2(depth.doubleValue()).toInt

    val enq_bits  = enq.filter { p =>
      (getReadyValidSfx(p.name) != "ready") && (getReadyValidSfx(p.name) != "valid")
    }
    val bitFields = enq_bits.map { eb =>
      val eblen = eb.name.length // enq_
      val fname = eb.name.substring(4, eblen)
      Field(fname, Default, eb.tpe)
    }

    val enqValidPort = Port(NoInfo, "io_enq_valid", Input, UIntW(1))
    val enqReadyPort = Port(NoInfo, "io_enq_ready", Output, UIntW(1))
    val enqBitsPorts = bitFields.map { case Field(fname, _, tpe) =>
      Port(NoInfo, "io_enq_bits_" + fname, Input, tpe)
    }

    val deqValidPort = Port(NoInfo, "io_deq_valid", Output, UIntW(1))
    val deqReadyPort = Port(NoInfo, "io_deq_ready", Input, UIntW(1))
    val deqBitsPorts = bitFields.map { case Field(fname, _, tpe) =>
      Port(NoInfo, "io_deq_bits_" + fname, Output, tpe)
    }

    val countPort = Port(NoInfo, "io_count", Output, UIntW(depthLog2 + 1))

    val ports =
      Seq(
        Port(NoInfo, "clock", Input, ClockType),
        Port(NoInfo, "reset", Input, firrtl.ir.UIntType(IntWidth(1))),
        countPort,
        enqValidPort,
        enqReadyPort,
        deqValidPort,
        deqReadyPort,
      ) ++
        deqBitsPorts ++
        enqBitsPorts

    val rams = bitFields.map { case Field(fname, _, tpe) =>
      DefMemory(
        NoInfo,
        "ram_" + fname,
        tpe,
        depth,
        1,
        0,
        Seq("io_deq_bits_MPORT"),
        Seq("MPORT"),
        Seq(),
        ReadUnderWrite.Undefined,
      )
    }

    val enq_ptr_value = DefRegister(
      NoInfo,
      "enq_ptr_value",
      UIntW(depthLog2),
      WRef("clock"),
      WRef("reset"),
      UIntLiteral(0, IntWidth(depthLog2)),
    )

    val deq_ptr_value = DefRegister(
      NoInfo,
      "deq_ptr_value",
      UIntW(depthLog2),
      WRef("clock"),
      WRef("reset"),
      UIntLiteral(0, IntWidth(depthLog2)),
    )

    val maybe_full = DefRegister(
      NoInfo,
      "maybe_full",
      UIntW(1),
      WRef("clock"),
      WRef("reset"),
      UIntLiteral(0, IntWidth(1)),
    )

    val ptr_match       = DefNode(
      NoInfo,
      "ptr_match",
      DoPrim(PrimOps.Eq, Seq(WRef(enq_ptr_value), WRef(deq_ptr_value)), Seq(), UIntW(1)),
    )
    val _empty_T        = DefNode(
      NoInfo,
      "_empty_T",
      DoPrim(PrimOps.Eq, Seq(WRef(maybe_full), UIntLiteral(0, IntWidth(1))), Seq(), UIntW(1)),
    )
    val empty           = DefNode(
      NoInfo,
      "empty",
      DoPrim(PrimOps.And, Seq(WRef(ptr_match), WRef(_empty_T)), Seq(), UIntW(1)),
    )
    val full            = DefNode(
      NoInfo,
      "full",
      DoPrim(PrimOps.And, Seq(WRef(ptr_match), WRef(maybe_full)), Seq(), UIntW(1)),
    )
    val _do_enq_T       = DefNode(
      NoInfo,
      "_do_enq_T",
      DoPrim(PrimOps.And, Seq(WRef(enqValidPort), WRef(enqReadyPort)), Seq(), UIntW(1)),
    )
    val _do_deq_T       = DefNode(
      NoInfo,
      "_do_deq_T",
      DoPrim(PrimOps.And, Seq(WRef(deqValidPort), WRef(deqReadyPort)), Seq(), UIntW(1)),
    )
    val _value_T        = DefNode(
      NoInfo,
      "_value_T",
      DoPrim(PrimOps.Add, Seq(WRef(enq_ptr_value), UIntLiteral(1, IntWidth(1))), Seq(), UIntW(depthLog2 + 1)),
    )
    val _value_T_1      = DefNode(
      NoInfo,
      "_value_T_1",
      DoPrim(PrimOps.Tail, Seq(WRef(_value_T)), Seq(1), UIntW(depthLog2)),
    )
    val _GEN_13         = DefNode(
      NoInfo,
      "_GEN_13",
      firrtl.ir.Mux(WRef(deqReadyPort), UIntLiteral(0, IntWidth(1)), WRef(_do_enq_T)),
    )
    val do_enq          = DefNode(
      NoInfo,
      "do_enq",
      firrtl.ir.Mux(WRef(empty), WRef(_GEN_13), WRef(_do_enq_T)),
    )
    val _GEN_6          = DefNode(
      NoInfo,
      "_GEN_6",
      firrtl.ir.Mux(WRef(do_enq), WRef(_value_T_1), WRef(enq_ptr_value)),
    )
    val _value_T_2      = DefNode(
      NoInfo,
      "_value_T_2",
      DoPrim(PrimOps.Add, Seq(WRef(deq_ptr_value), UIntLiteral(1, IntWidth(1))), Seq(), UIntW(depthLog2 + 1)),
    )
    val _value_T_3      = DefNode(
      NoInfo,
      "_value_T_3",
      DoPrim(PrimOps.Tail, Seq(WRef(_value_T_2)), Seq(1), UIntW(depthLog2)),
    )
    val do_deq          = DefNode(
      NoInfo,
      "do_deq",
      firrtl.ir.Mux(WRef(empty), UIntLiteral(0, IntWidth(1)), WRef(_do_deq_T)),
    )
    val _GEN_7          = DefNode(
      NoInfo,
      "_GEN_7",
      firrtl.ir.Mux(WRef(do_deq), WRef(_value_T_3), WRef(deq_ptr_value)),
    )
    val _T              = DefNode(
      NoInfo,
      "_T",
      DoPrim(PrimOps.Neq, Seq(WRef(do_enq), WRef(do_deq)), Seq(), UIntW(1)),
    )
    val _GEN_8          = DefNode(
      NoInfo,
      "_GEN_8",
      firrtl.ir.Mux(WRef(_T), WRef(do_enq), WRef(maybe_full)),
    )
    val _io_deq_valid_T = DefNode(
      NoInfo,
      "_io_deq_valid_T",
      DoPrim(PrimOps.Not, Seq(WRef(empty)), Seq(), UIntW(1)),
    )

    val _ptr_diff_T = DefNode(
      NoInfo,
      "_ptr_diff_T",
      DoPrim(PrimOps.Sub, Seq(WRef(enq_ptr_value), WRef(deq_ptr_value)), Seq(), UIntW(depthLog2 + 1)),
    )

    val ptr_diff      = DefNode(
      NoInfo,
      "ptr_diff",
      DoPrim(PrimOps.Tail, Seq(WRef(_ptr_diff_T)), Seq(1), UIntW(depthLog2)),
    )
    val _io_count_T   = DefNode(
      NoInfo,
      "_io_count_T",
      DoPrim(PrimOps.And, Seq(WRef(maybe_full), WRef(ptr_match)), Seq(), UIntW(1)),
    )
    val _io_count_T_1 = DefNode(
      NoInfo,
      "_io_count_T_1",
      firrtl.ir.Mux(
        WRef(_io_count_T),
        UIntLiteral(depth, IntWidth(depthLog2 + 1)),
        UIntLiteral(0, IntWidth(1)),
        UIntW(depthLog2 + 1),
      ),
    )

    val conn1 = Connect(
      NoInfo,
      WRef(enqReadyPort),
      DoPrim(PrimOps.Not, Seq(WRef(full)), Seq(), UIntW(1)),
    )
    val conn2 = Connect(
      NoInfo,
      WRef(deqValidPort),
      DoPrim(PrimOps.Or, Seq(WRef(enqValidPort), WRef(_io_deq_valid_T)), Seq(), UIntW(1)),
    )

    case class ZIPPED(ep: Port, dp: Port, ram: DefMemory)
    val connDeqDataBitsMPORT = enqBitsPorts.zip(deqBitsPorts).zip(rams).map { case ((ep, dp), ram) =>
      Connect(
        NoInfo,
        WRef(dp),
        firrtl.ir.Mux(WRef(empty), WRef(ep), WSubField(WSubField(WRef(ram), "io_deq_bits_MPORT"), "data")),
      )
    }
    val conn3                = Connect(
      NoInfo,
      WRef(countPort),
      DoPrim(PrimOps.Or, Seq(WRef(_io_count_T_1), WRef(ptr_diff)), Seq(), UIntW(depthLog2 + 1)),
    )

    val connDeqAddrBitsMPORT   = rams.map { ram =>
      Connect(NoInfo, WSubField(WSubField(WRef(ram), "io_deq_bits_MPORT"), "addr"), WRef(deq_ptr_value))
    }
    val connDeqEnableBitsMPORT = rams.map { ram =>
      Connect(NoInfo, WSubField(WSubField(WRef(ram), "io_deq_bits_MPORT"), "en"), UIntLiteral(1, IntWidth(1)))
    }
    val connDeqClockBitsMPORT  = rams.map { ram =>
      Connect(NoInfo, WSubField(WSubField(WRef(ram), "io_deq_bits_MPORT"), "clk"), WRef("clock"))
    }
    val connEnqAddrBitsMPORT   = rams.map { ram =>
      Connect(NoInfo, WSubField(WSubField(WRef(ram), "MPORT"), "addr"), WRef(enq_ptr_value))
    }
    val connEnqEnableBitsMPORT = rams.map { ram =>
      Connect(
        NoInfo,
        WSubField(WSubField(WRef(ram), "MPORT"), "en"),
        firrtl.ir.Mux(WRef(empty), WRef(_GEN_13), WRef(_do_enq_T)),
      )
    }
    val connEnqClockBitsMPORT  = rams.map { ram =>
      Connect(NoInfo, WSubField(WSubField(WRef(ram), "MPORT"), "clk"), WRef("clock"))
    }
    val connEnqDataBitsMPORT   = enqBitsPorts.zip(rams).map { case (ep, ram) =>
      Connect(NoInfo, WSubField(WSubField(WRef(ram), "MPORT"), "data"), WRef(ep))
    }

    val connEnqMaskBitsMPORT = rams.map { ram =>
      Connect(NoInfo, WSubField(WSubField(WRef(ram), "MPORT"), "mask"), UIntLiteral(1, IntWidth(1)))
    }

    val conn4 = Connect(NoInfo, WRef(enq_ptr_value), WRef(_GEN_6))
    val conn5 = Connect(NoInfo, WRef(deq_ptr_value), WRef(_GEN_7))
    val conn6 = Connect(NoInfo, WRef(maybe_full), WRef(_GEN_8))

    val connections = Seq(conn1, conn2, conn3, conn4, conn5, conn6)
    val body        =
      rams ++
        Seq(
          enq_ptr_value,
          deq_ptr_value,
          maybe_full,
          ptr_match,
          _empty_T,
          empty,
          full,
          _do_enq_T,
          _do_deq_T,
          _value_T,
          _value_T_1,
          _GEN_13,
          do_enq,
          _GEN_6,
          _value_T_2,
          _value_T_3,
          do_deq,
          _GEN_7,
          _T,
          _GEN_8,
          _io_deq_valid_T,
          _ptr_diff_T,
          ptr_diff,
          _io_count_T,
          _io_count_T_1,
        ) ++
        connections ++
        connDeqDataBitsMPORT ++
        connDeqAddrBitsMPORT ++
        connDeqEnableBitsMPORT ++
        connDeqClockBitsMPORT ++
        connEnqAddrBitsMPORT ++
        connEnqEnableBitsMPORT ++
        connEnqClockBitsMPORT ++
        connEnqDataBitsMPORT ++
        connEnqMaskBitsMPORT

    val flowQueue = Module(info = NoInfo, name = name, ports = ports, body = Block(body))
    flowQueue
  }

  def skidBufEnqPortName(p: WRef): String   = "enq_" + p.name
  def skidBufEnqPortName(s: String): String = "enq_" + s

  def skidBufDeqPortName(p: WRef): String   = "deq_" + p.name
  def skidBufDeqPortName(s: String): String = "deq_" + s

  def generateSkidBuffer(name: String, depth: Int, ports: Seq[WRef]): DefModule = {
    val enq = ports.map { p =>
      val rvsfx = getReadyValidSfx(p.name)
      if (rvsfx == "ready") Port(NoInfo, skidBufEnqPortName(p), Output, p.tpe)
      else Port(NoInfo, skidBufEnqPortName(p), Input, p.tpe)
    }

    val deq = ports.map { p =>
      val rvsfx = getReadyValidSfx(p.name)
      if (rvsfx == "ready") Port(NoInfo, skidBufDeqPortName(p), Input, p.tpe)
      else Port(NoInfo, skidBufDeqPortName(p), Output, p.tpe)
    }

    val clockReset = Seq(
      Port(NoInfo, "clock", Input, ClockType),
      Port(NoInfo, "reset", Input, firrtl.ir.UIntType(IntWidth(1))),
    )

    val deq_bits  = deq.filter(p =>
      (getReadyValidSfx(p.name) != "ready") &&
        (getReadyValidSfx(p.name) != "valid")
    )
    val deq_valid = deq.filter(p => (getReadyValidSfx(p.name) == "valid"))
    val deq_ready = deq.filter(p => (getReadyValidSfx(p.name) == "ready"))

    val enq_bits  = enq.filter(p =>
      (getReadyValidSfx(p.name) != "ready") &&
        (getReadyValidSfx(p.name) != "valid")
    )
    val enq_valid = enq.filter(p => (getReadyValidSfx(p.name) == "valid"))
    val enq_ready = enq.filter(p => (getReadyValidSfx(p.name) == "ready"))

    val queue     = DefInstance("buf", "Queue_" + name)
    val conQClock = Connect(NoInfo, WSubField(WRef(queue), "clock"), WRef("clock"))
    val conQReset = Connect(NoInfo, WSubField(WRef(queue), "reset"), WRef("reset"))

    // Connect deq ports of the skid-buffer to the queue
    val conDeqBits  = deq_bits.map { db =>
      val dbnamelen  = db.name.length
      val signalName = db.name.substring(4, dbnamelen)
      Connect(NoInfo, WRef(db), WSubField(WRef(queue), "io_deq_bits_" + signalName))
    }
    val conDeqValid = deq_valid.map { dv =>
      Connect(NoInfo, WRef(dv), WSubField(WRef(queue), "io_deq_valid"))
    }
    val conDeqReady = deq_ready.map { dr =>
      Connect(NoInfo, WSubField(WRef(queue), "io_deq_ready"), WRef(dr))
    }

    // Connect enq ports of the skid-buffer to the queue
    val conEnqBits = enq_bits.map { eb =>
      val ebnamelen  = eb.name.length
      val signalName = eb.name.substring(4, ebnamelen)
      Connect(NoInfo, WSubField(WRef(queue), "io_enq_bits_" + signalName), WRef(eb))
    }

    val conEnqValid = enq_valid.map { ev =>
      Connect(NoInfo, WSubField(WRef(queue), "io_enq_valid"), WRef(ev))
    }

    val enqReady = DefNode(
      NoInfo,
      "readyPropagate",
      DoPrim(PrimOps.Eq, Seq(WSubField(WRef(queue), "io_count"), UIntLiteral(0, IntWidth(1))), Seq(), UIntW(1)),
    )

    val conEnqReady = enq_ready.map { er =>
      Connect(NoInfo, WRef(er), WRef("readyPropagate"))
    }

    val queueEnqFire = DefNode(
      NoInfo,
      "skidbuf_queue_enq_fire",
      DoPrim(
        PrimOps.And,
        Seq(WSubField(WRef(queue), "io_enq_valid"), WSubField(WRef(queue), "io_enq_ready")),
        Seq(),
        UIntW(1),
      ),
    )

    val loginfoCycles = DefRegister(
      NoInfo,
      "loginfo_cycles",
      UIntW(64),
      WRef("clock"),
      WRef("reset"),
      UIntLiteral(0, IntWidth(0)),
    )

    val loginfoCyclesPlusOne = DefNode(
      NoInfo,
      "loginfo_cycles_next",
      DoPrim(PrimOps.Add, Seq(WRef(loginfoCycles), UIntLiteral(1, IntWidth(1))), Seq(), UIntW(64)),
    )

    val updateLoginfoCycles = Connect(NoInfo, WRef(loginfoCycles), WRef(loginfoCyclesPlusOne))

    val enqSignals = enq_bits.map { eb =>
      val ebnamelen = eb.name.length
      eb.name.substring(4, ebnamelen)
    }

    val enqPrintString =
      "cy: %d " +
        s"[${name}] EnqFire" +
        enqSignals
          .map { signalName =>
            s"${signalName} 0x%x"
          }
          .fold("") { (a, b) => a + " " + b } +
        " io_count: %d\n"

    val enqSignalValues =
      Seq(WRef(loginfoCycles)) ++
        enqSignals.map { signalName =>
          WSubField(WRef(queue), "io_enq_bits_" + signalName)
        }.toSeq ++
        Seq(WSubField(WRef(queue), "io_count"))

    val printQueueEnqFire = Print(NoInfo, StringLit(enqPrintString), enqSignalValues, WRef("clock"), WRef(queueEnqFire))

    val queueDeqFire = DefNode(
      NoInfo,
      "skidbuf_queue_deq_fire",
      DoPrim(
        PrimOps.And,
        Seq(WSubField(WRef(queue), "io_deq_valid"), WSubField(WRef(queue), "io_deq_ready")),
        Seq(),
        UIntW(1),
      ),
    )

    val deqSignals = deq_bits.map { eb =>
      val ebnamelen = eb.name.length
      eb.name.substring(4, ebnamelen)
    }

    val deqPrintString =
      "cy: %d " +
        s"[${name}] DeqFire" +
        deqSignals
          .map { signalName =>
            s"${signalName} 0x%x"
          }
          .fold("") { (a, b) => a + " " + b } +
        " io_count: %d\n"

    val deqSignalValues =
      Seq(WRef(loginfoCycles)) ++
        deqSignals.map { signalName =>
          WSubField(WRef(queue), "io_deq_bits_" + signalName)
        }.toSeq ++
        Seq(WSubField(WRef(queue), "io_count"))

    val printQueueDeqFire = Print(NoInfo, StringLit(deqPrintString), deqSignalValues, WRef("clock"), WRef(queueDeqFire))

    val stmts =
      Seq(queue, conQClock, conQReset) ++
        conDeqBits ++
        conDeqValid ++
        conDeqReady ++
        conEnqBits ++
        conEnqValid ++
        Seq(enqReady) ++
        conEnqReady ++
        Seq(loginfoCycles, loginfoCyclesPlusOne, updateLoginfoCycles) ++
        Seq(queueEnqFire, printQueueEnqFire) ++
        Seq(queueDeqFire, printQueueDeqFire)

    val skidBufferModule = Module(info = NoInfo, name = name, ports = enq ++ deq ++ clockReset, body = Block(stmts))
    queuesToAdd.append(generateQueue("Queue_" + name, depth, enq))
    skidBuffersToAdd.append(skidBufferModule)
    skidBufferModule
  }

  def findAndInsertSkidBuffers(
    wrapperModuleName:           String,
    insertDirection:             String,
  )(insertSkidBufferConnections: mutable.Map[String, String] => (Statement => Statement)
  )(m:                           DefModule
  ): DefModule = {
    val decoupledSignalsMap = mutable.Map[String, mutable.ArrayBuffer[WRef]]()

    def insertSkidBuffers(m: Module): Module = {
      collectDecoupledIO(m.ports, insertDirection, decoupledSignalsMap)

      // Create a WRef to the corresponding skid buffer mapping
      val wrefToSB  = mutable.Map[String, String]()
      val sbModules = decoupledSignalsMap.map { case (pfx, wrefList) =>
        val name = "SkidBuffer_" + pfx + s"_${idx}"
        wrefList.foreach(wref => wrefToSB(wref.name) = name)
        generateSkidBuffer(name, 2 * 32, wrefList.toSeq) // FIXME : Depth
      }

      val sbInstances = sbModules.map { m =>
        DefInstance(m.name, m.name)
      }

      println(s"${sbInstances.size} skid buffers generated")

      val sbClockResetConnections = sbInstances.map { inst =>
        Block(
          Seq(
            Connect(NoInfo, WSubField(WRef(inst), "clock"), WRef("clock")),
            Connect(NoInfo, WSubField(WRef(inst), "reset"), WRef("reset")),
          )
        )
      }

      val sbConnected = m.body.mapStmt(insertSkidBufferConnections(wrefToSB))
      val newBody     = (sbInstances ++ Seq(sbConnected) ++ sbClockResetConnections).toSeq
      m.copy(body = Block(newBody))
    }

    val newModule = m match {
      case mod: Module if (mod.name == wrapperModuleName) =>
        insertSkidBuffers(mod)
      case mod                                            => mod
    }

    newModule
  }

  def insertSkidBufferConnectionsForRemovePass(wrefToSB: mutable.Map[String, String])(stmt: Statement): Statement = {
    def replaceOutputConnection(io: String, iName: String, wName: String): Statement = {
      val sbInstance = wrefToSB(io)
      assert(sbInstance != None, s"Wrapper module missing WRef ${io}")

      val sbDeqConn = Connect(NoInfo, WRef(io), WSubField(WRef(sbInstance), skidBufDeqPortName(io)))
      val sbEnqConn =
        Connect(NoInfo, WSubField(WRef(sbInstance), skidBufEnqPortName(io)), WSubField(WRef(iName), wName))
      Block(Seq(sbDeqConn, sbEnqConn))
    }

    def replaceInputConnection(iName: String, wName: String, io: String): Statement = {
      val sbInstance = wrefToSB(io)
      assert(sbInstance != None, s"Wrapper module missing WRef ${io}")

      val sbDeqConn = Connect(NoInfo, WSubField(WRef(sbInstance), skidBufDeqPortName(io)), WRef(io))
      val sbEnqConn =
        Connect(NoInfo, WSubField(WRef(iName), wName), WSubField(WRef(sbInstance), skidBufEnqPortName(io)))
      Block(Seq(sbDeqConn, sbEnqConn))
    }

    stmt match {
      case Connect(_, WRef(io), WSubField(WRef(iName), wName, _, _)) =>
        if (wrefToSB.contains(io._1)) replaceOutputConnection(io._1, iName._1, wName) else stmt
      case Connect(_, WSubField(WRef(iName), wName, _, _), WRef(io)) =>
        if (wrefToSB.contains(io._1)) replaceInputConnection(iName._1, wName, io._1) else stmt
      case s: DefInstance                                            => s
      case s: DefWire                                                => s
      case s: DefRegister                                            => s
      case s: DefMemory                                              => s
      case s: DefNode                                                => s
      case s: Conditionally                                          => s
      case s: PartialConnect                                         => s
      case s: Connect                                                => s
      case s: IsInvalid                                              => s
      case s: Attach                                                 => s
      case s: Stop                                                   => s
      case s: Print                                                  => s
      case s: Verification                                           => s
      case s                                                         => s.map(insertSkidBufferConnectionsForRemovePass(wrefToSB))
    }
  }

  def insertSkidBufferConnectionsForExtractPass(wrefToSB: mutable.Map[String, String])(stmt: Statement): Statement = {
    def replaceOutputConnection(io: String, iName: String, wName: String): Statement = {
      val sbInstance = wrefToSB(io)
      assert(sbInstance != None, "Wrapper module missing WRef ${io}")

      val sbDeqConn = Connect(NoInfo, WRef(io), WSubField(WRef(sbInstance), skidBufEnqPortName(io)))
      val sbEnqConn =
        Connect(NoInfo, WSubField(WRef(sbInstance), skidBufDeqPortName(io)), WSubField(WRef(iName), wName))
      Block(Seq(sbDeqConn, sbEnqConn))
    }

    def replaceInputConnection(iName: String, wName: String, io: String): Statement = {
      val sbInstance = wrefToSB(io)
      assert(sbInstance != None, "Wrapper module missing WRef ${io}")

      val sbDeqConn = Connect(NoInfo, WSubField(WRef(sbInstance), skidBufEnqPortName(io)), WRef(io))
      val sbEnqConn =
        Connect(NoInfo, WSubField(WRef(iName), wName), WSubField(WRef(sbInstance), skidBufDeqPortName(io)))
      Block(Seq(sbDeqConn, sbEnqConn))
    }

    stmt match {
      case Connect(_, WRef(io), WSubField(WRef(iName), wName, _, _)) =>
        if (wrefToSB.contains(io._1)) replaceOutputConnection(io._1, iName._1, wName) else stmt
      case Connect(_, WSubField(WRef(iName), wName, _, _), WRef(io)) =>
        if (wrefToSB.contains(io._1)) replaceInputConnection(iName._1, wName, io._1) else stmt
      case s: DefInstance                                            => s
      case s: DefWire                                                => s
      case s: DefRegister                                            => s
      case s: DefMemory                                              => s
      case s: DefNode                                                => s
      case s: Conditionally                                          => s
      case s: PartialConnect                                         => s
      case s: Connect                                                => s
      case s: IsInvalid                                              => s
      case s: Attach                                                 => s
      case s: Stop                                                   => s
      case s: Print                                                  => s
      case s: Verification                                           => s
      case s                                                         => s.map(insertSkidBufferConnectionsForExtractPass(wrefToSB))
    }
  }

  def findAndReplaceValidOutToFire(wrapperModuleName: String, replaceForOutgoing: Boolean)(m: DefModule): DefModule = {
    val decoupledSignalsMap = mutable.Map[String, mutable.ArrayBuffer[WRef]]()

    def replaceValidOutToFire(m: Module): Module = {
      val replaceDirection = if (replaceForOutgoing) "output" else "input"
      collectDecoupledIO(m.ports, replaceDirection, decoupledSignalsMap)

      val instanceNames = mutable.ArrayBuffer[String]()
      def findInstanceName(iNames: mutable.ArrayBuffer[String])(stmt: Statement): Statement = {
        stmt match {
          case s: DefInstance    =>
            iNames.append(s.name)
            s
          case s: DefWire        => s
          case s: DefRegister    => s
          case s: DefMemory      => s
          case s: DefNode        => s
          case s: Conditionally  => s
          case s: PartialConnect => s
          case s: Connect        => s
          case s: IsInvalid      => s
          case s: Attach         => s
          case s: Stop           => s
          case s: Print          => s
          case s: Verification   => s
          case s                 => findInstanceName(iNames)(s)
        }
      }

      m.body.map(findInstanceName(instanceNames)(_))
// println(instanceNames)
      assert(instanceNames.size == 1, "There should be a single instance inside the wrapper module")
      val instanceName = instanceNames.head

      val rvs                 = decoupledSignalsMap.map { case (pfx, _) => (pfx + "valid", pfx + "ready") }
      val validToFireDefNodes = rvs.map { case (valid, ready) =>
        val fireNode = if (replaceForOutgoing) {
          DefNode(
            NoInfo,
            valid + "_fire",
            DoPrim(
              PrimOps.And,
              Seq(WSubField(WRef(instanceName), valid), WSubField(WRef(instanceName), ready)),
              Seq(),
              UIntW(1),
            ),
          )
        } else {
          DefNode(NoInfo, valid + "_fire", DoPrim(PrimOps.And, Seq(WRef(valid), WRef(ready)), Seq(), UIntW(1)))
        }
        valid -> fireNode
      }.toMap

      val validReplacedToFire = m.body.mapStmt(replaceValidOutConnectionsToFire(validToFireDefNodes))
      m.copy(body = Block(validReplacedToFire))
    }

    val newModule = m match {
      case mod: Module if (mod.name == wrapperModuleName) =>
        replaceValidOutToFire(mod)
      case mod                                            => mod
    }
    newModule
  }

  def replaceValidOutConnectionsToFire(validsToReplace: Map[String, DefNode])(stmt: Statement): Statement = {
    def replaceOutputConnection(io: String, iName: String, wName: String): Statement = {
      val fire = io + "_fire"
      assert(fire != None, s"Wrapper module missing corresponding WRef fire for ${io}")
      Block(
        Seq(
          validsToReplace(io),
          Print(NoInfo, StringLit(s"${io} fire!\n"), Seq(), WRef("clock"), WRef(fire)),
          Connect(NoInfo, WRef(io), WRef(fire)),
        )
      )
    }

    def replaceInputConnection(iName: String, wName: String, io: String): Statement = {
      val fire = io + "_fire"
      assert(fire != None, s"Wrapper module missing corresponding WRef fire for ${io}")
      Block(
        Seq(
          validsToReplace(io),
          Print(NoInfo, StringLit(s"${io} fire!\n"), Seq(), WRef("clock"), WRef(fire)),
          Connect(NoInfo, WSubField(WRef(iName), wName), WRef(fire)),
        )
      )
    }

    stmt match {
      case Connect(_, WRef(io), WSubField(WRef(iName), wName, _, _)) =>
        if (validsToReplace.contains(io._1)) {
          replaceOutputConnection(io._1, iName._1, wName)
        } else {
          stmt
        }
      case Connect(_, WSubField(WRef(iName), wName, _, _), WRef(io)) =>
        if (validsToReplace.contains(io._1)) {
          replaceInputConnection(iName._1, wName, io._1)
        } else {
          stmt
        }
      case s: DefInstance                                            => s
      case s: DefWire                                                => s
      case s: DefRegister                                            => s
      case s: DefMemory                                              => s
      case s: DefNode                                                => s
      case s: Conditionally                                          => s
      case s: PartialConnect                                         => s
      case s: Connect                                                => s
      case s: IsInvalid                                              => s
      case s: Attach                                                 => s
      case s: Stop                                                   => s
      case s: Print                                                  => s
      case s: Verification                                           => s
      case s                                                         => s.map(replaceValidOutConnectionsToFire(validsToReplace))
    }
  }

  private def initState(): Unit = {
    skidBuffersToAdd.clear()
    queuesToAdd.clear()
    idx += 1
  }

  def insertSkidBuffersToWrapper(
    state:             CircuitState,
    wrapperModuleName: String,
    insertForIncoming: Boolean,
  ): CircuitState = {
    val validOutReplacedToFireCircuit = state.circuit.map {
      findAndReplaceValidOutToFire(wrapperModuleName, insertForIncoming)(_)
    }

    val insertDirection            = if (insertForIncoming) "input" else "output"
    val insertSkidBufferConnection = if (insertForIncoming) {
      insertSkidBufferConnectionsForExtractPass _
    } else {
      insertSkidBufferConnectionsForRemovePass _
    }

    val skidBufferInsertedCircuit = validOutReplacedToFireCircuit.map {
      findAndInsertSkidBuffers(wrapperModuleName, insertDirection)(insertSkidBufferConnection)(_)
    }

    val skidBufferInsertedState = state.copy(circuit = skidBufferInsertedCircuit)
    val allModuleList           = skidBufferInsertedState.circuit.modules ++ skidBuffersToAdd ++ queuesToAdd
    val modListUpdatedCircuit   = skidBufferInsertedState.circuit.copy(modules = allModuleList)
    val newState                = skidBufferInsertedState.copy(circuit = modListUpdatedCircuit)

    initState()

    newState
  }
}
