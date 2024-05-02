// package midas.widgets


// import chisel3._
// import chisel3.util._
// import org.chipsalliance.cde.config._
// import freechips.rocketchip.util._
// import midas.MetasimPrintfEnable


// import TokensBatchedAtOnceConsts._









// case class TPCutBoundaryParams(
// srcInTokenBits: Int,
// srcFromHostBits: Int,
// srcOutTokenBits: Int,
// srcToHostBits: Int,
// sinkInTokenBits: Int,
// sinkFromHostBits: Int,
// sinkOutTokenBits: Int,
// sinkToHostBits: Int)

// case class TPCutBoundaryKey(cutParams: TPCutBoundaryParams)

// class TPCutBoundaryBridgeIO(cutParams: TPCutBoundaryParams) extends Bundle {
// val clock    = Input(Clock())
// val reset    = Input(Bool())
// val src_out  = Input(UInt(cutParams.srcOutTokenBits.W))
// val src_in   = Output(UInt(cutParams.srcInTokenBits.W))
// val sink_out = Input(UInt(cutParams.sinkOutTokenBits.W))
// val sink_in  = Output(UInt(cutParams.sinkInTokenBits.W))
// }

// class QSFPTPCutBoundaryBridge(cutParams: TPCutBoundaryParams)(implicit p: Parameters)
// extends BlackBox
// with Bridge[HostPortIO[TPCutBoundaryBridgeIO], QSFPTPCutBoundaryBridgeModule] {
// val io = IO(new TPCutBoundaryBridgeIO(cutParams))
// val bridgeIO = HostPort(io)
// val constructorArg = Some(TPCutBoundaryKey(cutParams))
// generateAnnotations()
// }

// abstract class TPCutBoundaryBridgeModule(
// key: TPCutBoundaryKey
// )(
// implicit p: Parameters
// ) extends BridgeModule[HostPortIO[TPCutBoundaryBridgeIO]]()(p)
// with StreamFrom
// with StreamTo
// {
// import DMAUtil._
// import TokensBatchedAtOnceConsts._

// val params = key.cutParams

// val streamBits = BridgeStreamConstants.streamWidthBits

// val tagBits = 1
// val srcInTokenBits   = params.srcInTokenBits   + tagBits
// val srcOutTokenBits  = params.srcOutTokenBits  + tagBits
// val sinkInTokenBits  = params.sinkInTokenBits  + tagBits
// val sinkOutTokenBits = params.sinkOutTokenBits + tagBits

// val streamPerSrcIn   = DMAsPerToken(srcInTokenBits,   streamBits)
// val streamPerSrcOut  = DMAsPerToken(srcOutTokenBits,  streamBits)
// val streamPerSinkIn  = DMAsPerToken(sinkInTokenBits,  streamBits)
// val streamPerSinkOut = DMAsPerToken(sinkOutTokenBits, streamBits)

// val streamSrcInDepth   = 2 * TOKEN_QUEUE_DEPTH * streamPerSrcIn
// val streamSrcOutDepth  = 2 * TOKEN_QUEUE_DEPTH * streamPerSrcOut
// val streamSinkInDepth  = 2 * TOKEN_QUEUE_DEPTH * streamPerSinkIn
// val streamSinkOutDepth = 2 * TOKEN_QUEUE_DEPTH * streamPerSinkOut

// def bridgeDriverClassName: String
// def bridgeDriverHeaderName: String
// def bridgeDriverArgs: Seq[CPPLiteral]

// lazy val module = new BridgeModuleImp(this) {
// val io = IO(new WidgetIO())
// val hPort = IO(HostPort(new TPCutBoundaryBridgeIO(params)))

// val srcInTokenQ   = Module(new Queue(UInt(srcInTokenBits.W),   TOKEN_QUEUE_DEPTH))
// val srcOutTokenQ  = Module(new Queue(UInt(srcOutTokenBits.W),  TOKEN_QUEUE_DEPTH))
// val sinkInTokenQ  = Module(new Queue(UInt(sinkInTokenBits.W),  TOKEN_QUEUE_DEPTH))
// val sinkOutTokenQ = Module(new Queue(UInt(sinkOutTokenBits.W), TOKEN_QUEUE_DEPTH))

// val streamSrcInQ   = Module(new Queue(UInt(streamBits.W), streamSrcInDepth))
// val streamSrcOutQ  = Module(new Queue(UInt(streamBits.W), streamSrcOutDepth))
// val streamSinkInQ  = Module(new Queue(UInt(streamBits.W), streamSinkInDepth))
// val streamSinkOutQ = Module(new Queue(UInt(streamBits.W), streamSinkOutDepth))

// val srcInFire = DecoupledHelper(
// hPort.
// }
// }
