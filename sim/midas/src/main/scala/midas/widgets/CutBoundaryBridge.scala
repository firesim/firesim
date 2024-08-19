// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import freechips.rocketchip.util._

import midas.{MetasimPrintfEnable, PrintfLogger}

import firesim.lib.bridgeutils._

object TokensBatchedAtOnceConsts {
  val TOKEN_QUEUE_DEPTH = 32 * 2
}
import TokensBatchedAtOnceConsts._

object DMAUtil {
  def DMAsPerToken(tokenBits: Int, dmaBits: Int): Int = {
    val bitRatio     = tokenBits / dmaBits
    val transactions = if (tokenBits % dmaBits == 0) bitRatio else (bitRatio + 1)
    println(s"DMAUtil token.W ${tokenBits} dmaBits.W ${dmaBits} bitRatio ${bitRatio} #transactions ${transactions}")
    transactions
  }
}
import DMAUtil._

case class CutBoundaryParams(
  inTokenBits:  Int,
  fromHostBits: Int,
  outTokenBits: Int,
  toHostBits:   Int,
)
case class CutBoundaryKey(cutParams: CutBoundaryParams)

// Module -> outBits
// Module <- inBits
class CutBoundaryBridgeIO(cutParams: CutBoundaryParams) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val out   = Input(UInt(cutParams.outTokenBits.W))
  val in    = Output(UInt(cutParams.inTokenBits.W))
}

class PCISCutBoundaryBridge(cutParams: CutBoundaryParams)
    extends BlackBox
    with Bridge[HostPortIO[CutBoundaryBridgeIO]] {
  val moduleName     = "midas.widgets.PCISCutBoundaryBridgeModule"
  val io             = IO(new CutBoundaryBridgeIO(cutParams))
  val bridgeIO       = HostPort(io)
  val constructorArg = Some(CutBoundaryKey(cutParams))
  generateAnnotations()
}

class QSFPCutBoundaryBridge(cutParams: CutBoundaryParams)
    extends BlackBox
    with Bridge[HostPortIO[CutBoundaryBridgeIO]] {
  val moduleName     = "midas.widgets.QSFPCutBoundaryBridgeModule"
  val io             = IO(new CutBoundaryBridgeIO(cutParams))
  val bridgeIO       = HostPort(io)
  val constructorArg = Some(CutBoundaryKey(cutParams))
  generateAnnotations()
}

class PCIMCutBoundaryBridge(cutParams: CutBoundaryParams)
    extends BlackBox
    with Bridge[HostPortIO[CutBoundaryBridgeIO]] {
  val moduleName     = "midas.widgets.PCIMCutBoundaryBridgeModule"
  val io             = IO(new CutBoundaryBridgeIO(cutParams))
  val bridgeIO       = HostPort(io)
  val constructorArg = Some(CutBoundaryKey(cutParams))
  generateAnnotations()
}

class TokenSlicer(
  toHostBits: Int,
  tagBits:    Int,
  tokenBits:  Int,
  tag:        BigInt,
) extends Module {
  val streamBits         = toHostBits + tagBits
  val io                 = IO(new Bundle {
    val token  = Flipped(Decoupled(UInt(tokenBits.W)))
    val toHost = Decoupled(UInt(streamBits.W))
  })
  val slicesToSend       = DMAsPerToken(tokenBits, toHostBits)
  val slicesToSendMinus1 = slicesToSend - 1

  val TAG = tag.U(tagBits.W)

  if (slicesToSendMinus1 == 0) {
    io.toHost.valid := io.token.valid
    io.token.ready  := io.toHost.ready
    io.toHost.bits  := Cat(io.token.bits, TAG)
  } else {
    val sentCnt        = RegInit(0.U(log2Ceil(slicesToSend + 1).W))
    val shiftRightBits = Wire(UInt(log2Ceil(tokenBits + 1).W))
    shiftRightBits := sentCnt * toHostBits.U(log2Ceil(toHostBits + 1).W)

    val curBitsToSend = Wire(UInt(toHostBits.W))
    curBitsToSend := io.token.bits >> shiftRightBits

    when(io.toHost.fire) {
      sentCnt := Mux(sentCnt === slicesToSendMinus1.U, 0.U, sentCnt + 1.U)
    }

    io.token.ready  := io.toHost.ready && (sentCnt === slicesToSendMinus1.U)
    io.toHost.bits  := Cat(curBitsToSend, TAG)
    io.toHost.valid := io.token.valid
  }
}

class TokenAggregator(
  fromHostBits: Int,
  tagBits:      Int,
  tokenBits:    Int,
) extends Module {
  val streamBits         = fromHostBits + tagBits
  val io                 = IO(new Bundle {
    val fromHost = Flipped(Decoupled(UInt(streamBits.W)))
    val token    = Decoupled(UInt(tokenBits.W))
  })
  val numAggregate       = DMAsPerToken(tokenBits, fromHostBits)
  val numAggregateMinus1 = numAggregate - 1

  if (numAggregateMinus1 == 0) {
    io.token.valid    := io.fromHost.valid
    io.token.bits     := io.fromHost.bits >> tagBits
    io.fromHost.ready := io.token.ready
  } else {
    val aggregatedCnt = RegInit(0.U(log2Ceil(numAggregate + 1).W))
    val aggregateBits = Seq.fill(numAggregateMinus1)(RegInit(0.U(fromHostBits.W)))

    io.fromHost.ready := (aggregatedCnt =/= numAggregateMinus1.U) ||
      (aggregatedCnt === numAggregateMinus1.U && io.token.ready)

    when(io.fromHost.fire) {
      aggregatedCnt := Mux(aggregatedCnt === numAggregateMinus1.U, 0.U, aggregatedCnt + 1.U)
    }

    for (i <- 0 until numAggregateMinus1) {
      when(i.U === aggregatedCnt) {
        aggregateBits(i) := io.fromHost.bits >> tagBits
      }
    }

    val outBits = Wire(UInt(tokenBits.W))
    outBits := Cat(io.fromHost.bits >> tagBits, Cat(aggregateBits.reverse))

    io.token.valid := io.fromHost.valid && (aggregatedCnt === numAggregateMinus1.U)
    io.token.bits  := outBits
  }
}

abstract class CutBoundaryBridgeModule(
  key:        CutBoundaryKey
)(implicit p: Parameters
) extends BridgeModule[HostPortIO[CutBoundaryBridgeIO]]()(p)
    with StreamFrom
    with StreamTo {

  val cutParams    = key.cutParams
  val inTokenBits  = cutParams.inTokenBits
  val outTokenBits = cutParams.outTokenBits

  val streamToHostBits   = toHostStreamWidthBits
  val streamFromHostBits = fromHostStreamWidthBits
  val streamBits         = streamToHostBits
  require(streamToHostBits == streamFromHostBits)

  val tagBits      = 32
  val fromHostBits = streamBits - tagBits
  val toHostBits   = streamBits - tagBits
  val TAG          = BigInt("ABCD", 16)

  val fromHostDMATransactions = DMAsPerToken(inTokenBits, fromHostBits)
  val toHostDMATransactions   = DMAsPerToken(outTokenBits, toHostBits)

  val cutInQueueDepth  = TOKEN_QUEUE_DEPTH * fromHostDMATransactions * 2
  val cutOutQueueDepth = TOKEN_QUEUE_DEPTH * toHostDMATransactions * 2

  def bridgeDriverClassName:  String
  def bridgeDriverHeaderName: String
  def bridgeDriverArgs:       Seq[CPPLiteral]

  lazy val module = new BridgeModuleImp(this) {
    val io    = IO(new WidgetIO())
    val hPort = IO(HostPort(new CutBoundaryBridgeIO(cutParams)))

    val tokenOutQueue = Module(new Queue(UInt(outTokenBits.W), TOKEN_QUEUE_DEPTH))
    val tokenInQueue  = Module(new Queue(UInt(inTokenBits.W), TOKEN_QUEUE_DEPTH))

    val filterQueue = Module(new Queue(UInt(streamBits.W), cutInQueueDepth))
    val cutInQueue  = Module(new Queue(UInt(streamBits.W), cutInQueueDepth))
    val cutOutQueue = Module(new Queue(UInt(streamBits.W), cutOutQueueDepth))

    val tokenSlicer     = Module(new TokenSlicer(toHostBits, tagBits, outTokenBits, TAG))
    val tokenAggregator = Module(new TokenAggregator(fromHostBits, tagBits, inTokenBits))

    val inputTokens  = RegInit(0.U(64.W))
    val outputTokens = RegInit(0.U(64.W))

    val toHostFireCnt    = RegInit(0.U(64.W))
    val assertToHostEq   = RegInit(false.B)
    val fromHostFireCnt  = RegInit(0.U(64.W))
    val assertFromHostEq = RegInit(false.B)

    assertFromHostEq := Mux(!assertFromHostEq, fromHostFireCnt =/= inputTokens, assertFromHostEq)
    assertToHostEq   := Mux(!assertToHostEq, toHostFireCnt =/= outputTokens, assertToHostEq)

    attach(inputTokens, "input_tokens", ReadOnly)
    attach(outputTokens, "output_tokens", ReadOnly)
    attach(tokenOutQueue.io.count, "tokenOutQueue_io_count", ReadOnly)
    attach(tokenInQueue.io.count, "tokenInQueue_io_count", ReadOnly)
    attach(cutInQueue.io.count, "cutInQueue_io_count", ReadOnly)
    attach(cutOutQueue.io.count, "cutOutQueue_io_count", ReadOnly)
    attach(toHostFireCnt, "to_host_fire_count", ReadOnly)
    attach(fromHostFireCnt, "from_host_fire_count", ReadOnly)
    attach(assertToHostEq, "assert_to_host_eq", ReadOnly)
    attach(assertFromHostEq, "assert_from_host_eq", ReadOnly)

    val initSimulatorDone        = RegInit(false.B)
    val initSimulatorTokens      = RegInit(0.U(64.W))
    val initSimulatorTokensValid = RegInit(false.B)
    val curInitTokens            = RegInit(0.U(64.W))
    val combInitTokens           = RegInit(0.U(64.W))
    val initToken                = ((BigInt(1) << inTokenBits) - BigInt(1)).U

    attach(initSimulatorTokens, "init_simulator_tokens", WriteOnly)
    attach(initSimulatorTokensValid, "init_simulator_tokens_valid", WriteOnly)
    attach(curInitTokens, "cur_init_tokens", ReadOnly)
    attach(combInitTokens, "comb_init_tokens", ReadOnly)

    val enqTokenFire = DecoupledHelper(hPort.fromHost.hReady, tokenInQueue.io.deq.valid, outputTokens >= inputTokens)

    val deqTokenFire = DecoupledHelper(hPort.toHost.hValid, tokenOutQueue.io.enq.ready, initSimulatorDone)

    val metasimPrintfEnable = p(MetasimPrintfEnable)
    when(deqTokenFire.fire()) {
      outputTokens := outputTokens + 1.U
      if (metasimPrintfEnable) {
        PrintfLogger.logInfo("CutBoundaryBridge %d toHostTokenFire 0x%x\n", outTokenBits.U, hPort.hBits.out)
      }
    }

    when(enqTokenFire.fire()) {
      inputTokens := inputTokens + 1.U
      if (metasimPrintfEnable) {
        PrintfLogger.logInfo("CutBoundaryBridge %d fromHostTokenFire 0x%x\n", inTokenBits.U, hPort.hBits.in)
      }
    }

    when(hPort.toHost.hValid && hPort.toHost.hReady) {
      toHostFireCnt := toHostFireCnt + 1.U
    }
    when(hPort.fromHost.hValid && hPort.fromHost.hValid) {
      fromHostFireCnt := fromHostFireCnt + 1.U
    }

    // target to tokenOutQueue
    tokenOutQueue.io.enq.valid := deqTokenFire.fire(tokenOutQueue.io.enq.ready)
    tokenOutQueue.io.enq.bits  := hPort.hBits.out
    hPort.toHost.hReady        := deqTokenFire.fire(hPort.toHost.hValid)

    // tokenOutQueue to tokenSlicer
    tokenSlicer.io.token <> tokenOutQueue.io.deq

    // tokenSlicer to Queue
    cutOutQueue.io.enq <> tokenSlicer.io.toHost

    // Queue to host
    streamEnq <> cutOutQueue.io.deq

    // Enqueue all incoming stuff into a queue
    filterQueue.io.enq <> streamDeq

    val UIntTAG            = TAG.U(tagBits.W)
    val validRx            = filterQueue.io.deq.bits(tagBits - 1, 0) === UIntTAG
    val acceptRxStreamFire = DecoupledHelper(filterQueue.io.deq.valid, cutInQueue.io.enq.ready, validRx)

    val rejectRxStreamFire = DecoupledHelper(filterQueue.io.deq.valid, !validRx)

    // Choose valid requests from the filterQueue into the cutInQUeue
    cutInQueue.io.enq.valid  := acceptRxStreamFire.fire(cutInQueue.io.enq.ready)
    cutInQueue.io.enq.bits   := filterQueue.io.deq.bits
    filterQueue.io.deq.ready := acceptRxStreamFire.fire(filterQueue.io.deq.valid) ||
      rejectRxStreamFire.fire(filterQueue.io.deq.valid)

    // For QSFP bridges, there are cases where we receive gargabe data
    // from the Aurora IP.
    val garbageCnt = RegInit(0.U(64.W))
    when(rejectRxStreamFire.fire()) {
      garbageCnt := garbageCnt + 1.U
      if (metasimPrintfEnable) {
        PrintfLogger.logInfo("rejectToken(%d) 0x%x\n", garbageCnt, filterQueue.io.deq.bits)
      }
    }
    attach(garbageCnt, "garbage_rx_cnt", ReadOnly)

    // queue to tokenAggregator
    tokenAggregator.io.fromHost <> cutInQueue.io.deq

    val initTokenFire = DecoupledHelper(
      tokenInQueue.io.enq.ready,
      initSimulatorTokensValid,
      curInitTokens < initSimulatorTokens,
      !initSimulatorDone,
    )

    val exchangeTokenFire = DecoupledHelper(
      tokenInQueue.io.enq.ready,
      tokenAggregator.io.token.valid,
      initSimulatorTokensValid,
      initSimulatorDone,
    )

    tokenInQueue.io.enq.valid      := initTokenFire.fire(tokenInQueue.io.enq.ready) ||
      exchangeTokenFire.fire(tokenInQueue.io.enq.ready)
    tokenInQueue.io.enq.bits       := Mux(initTokenFire.fire(), initToken, tokenAggregator.io.token.bits)
    tokenAggregator.io.token.ready := exchangeTokenFire.fire(tokenAggregator.io.token.valid)

    when(initTokenFire.fire()) {
      curInitTokens := curInitTokens + 1.U
      when(curInitTokens === initSimulatorTokens - 1.U) {
        initSimulatorDone := true.B
      }
      if (metasimPrintfEnable) {
        PrintfLogger.logInfo("InitTokenEnq %d\n", curInitTokens)
      }
    }.elsewhen(initSimulatorTokensValid && initSimulatorTokens === 0.U) {
      initSimulatorDone := true.B
    }

    // tokenInQueue to target
    tokenInQueue.io.deq.ready := enqTokenFire.fire(tokenInQueue.io.deq.valid)
    hPort.fromHost.hValid     := enqTokenFire.fire(hPort.fromHost.hReady)
    hPort.hBits.in            := tokenInQueue.io.deq.bits

    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      genConstructor(
        base,
        sb,
        bridgeDriverClassName,
        bridgeDriverHeaderName,
        bridgeDriverArgs,
        hasStreams = true,
      )
    }

    override def genPartitioningConstants(sb: StringBuilder): Unit = {
      println(s"wrapper.instanceName ${wrapper.instanceName}")
      val idx = wrapper.instanceName.split("\\D+").filter(_.nonEmpty).toList.last.toInt
      sb.append(s"#define TO_HOST_TRANSACTIONS_${idx}   ${toHostDMATransactions}\n")
      sb.append(s"#define FROM_HOST_TRANSACTIONS_${idx} ${fromHostDMATransactions}\n")
    }
  }
}

class PCIMCutBoundaryBridgeModule(key: CutBoundaryKey)(implicit p: Parameters)
    extends CutBoundaryBridgeModule(key)(p)
    with StreamToPeerFPGA
    with StreamFromHostCPU {

  val fromHostCPUQueueDepth       = TOKEN_QUEUE_DEPTH * fromHostDMATransactions * 2
  val peerFPGAMaxAddrRangeInBeats = TOKEN_QUEUE_DEPTH * toHostDMATransactions * 2

  override def bridgeDriverClassName  = "pcim_cutbridge_t"
  override def bridgeDriverHeaderName = "pcim_cutbridge"
  override def bridgeDriverArgs       = Seq()
}

class QSFPCutBoundaryBridgeModule(key: CutBoundaryKey)(implicit p: Parameters)
    extends CutBoundaryBridgeModule(key)(p)
    with StreamToQSFP
    with StreamFromQSFP {

  val fromQSFPQueueDepth = TOKEN_QUEUE_DEPTH * fromHostDMATransactions * 2
  val toQSFPQueueDepth   = TOKEN_QUEUE_DEPTH * toHostDMATransactions * 2

  override def bridgeDriverClassName  = "qsfp_cutbridge_t"
  override def bridgeDriverHeaderName = "qsfp_cutbridge"
  override def bridgeDriverArgs       = Seq()
}

class PCISCutBoundaryBridgeModule(key: CutBoundaryKey)(implicit p: Parameters)
    extends CutBoundaryBridgeModule(key)(p)
    with StreamToHostCPU
    with StreamFromHostCPU {

  val fromHostCPUQueueDepth = TOKEN_QUEUE_DEPTH * fromHostDMATransactions * 2
  val toHostCPUQueueDepth   = TOKEN_QUEUE_DEPTH * toHostDMATransactions * 2

  override def bridgeDriverClassName  = "pcis_cutbridge_t"
  override def bridgeDriverHeaderName = "pcis_cutbridge"
  override def bridgeDriverArgs       = Seq(
    UInt32(toHostStreamIdx),
    UInt32(toHostDMATransactions),
    UInt32(fromHostStreamIdx),
    UInt32(fromHostDMATransactions),
  )
}
