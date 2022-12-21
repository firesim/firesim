// See LICENSE for license details.

package midas
package widgets

import midas.core.{TargetChannelIO}

import freechips.rocketchip.config.{Parameters, Field, Config}

import chisel3._
import chisel3.util._
import chisel3.experimental.{BaseModule, Direction, ChiselAnnotation, annotate}
import firrtl.annotations.{ReferenceTarget, ModuleTarget, JsonProtocol, HasSerializationHints}
import freechips.rocketchip.util.WideCounter

import scala.reflect.runtime.{universe => ru}

import scala.collection.mutable

/* Bridge
 *
 * Bridges are widgets that operate directly on token streams moving to and
 * from the transformed-RTL model.
 *
 */

// Set in FPGA Top before the BridgeModule is generated
case object TargetClockInfo extends Field[Option[RationalClock]]

abstract class BridgeModule[HostPortType <: Record with HasChannels]()(implicit p: Parameters) extends Widget()(p) {
  def module: BridgeModuleImp[HostPortType]
}


case object InsertTokenHashersKey extends Field[Boolean](false)
case object TokenHashersUseCounter extends Field[Boolean](false)
case object TokenHashersDepth extends Field[Int](1024)


class TokenHasherControlBundle extends Bundle {
  val triggerDelay = Input(UInt(64.W))
  val triggerPeriod = Input(UInt(64.W))
}

case class TokenHasherMeta(
  bridgeName:     String,  // the name of bridge
  name:           String,  // the name of the channel
  output:         Boolean, // true if this is an output port
  queueHead:      BigInt,  // MMIO address of the queue head
  queueOccupancy: BigInt,
  tokenCount0:    BigInt,
  tokenCount1:    BigInt
) {
  def offset(base: BigInt): TokenHasherMeta = {
    return new TokenHasherMeta(bridgeName, name, output, 
    base + queueHead,
    base + queueOccupancy,
    base + tokenCount0,
    base + tokenCount1);
  }
}

abstract class BridgeModuleImp[HostPortType <: Record with HasChannels]
    (wrapper: BridgeModule[_ <: HostPortType])
    (implicit p: Parameters) extends WidgetImp(wrapper) {
  def hPort: HostPortType
  def clockDomainInfo: RationalClock = p(TargetClockInfo).get
  def emitClockDomainInfo(headerWidgetName: String, sb: StringBuilder): Unit = {
    import CppGenerationUtils._
    val RationalClock(domainName, mul, div) = clockDomainInfo
    sb.append(genStatic(s"${headerWidgetName}_clock_domain_name", CStrLit(domainName)))
    sb.append(genConstStatic(s"${headerWidgetName}_clock_multiplier", UInt32(mul)))
    sb.append(genConstStatic(s"${headerWidgetName}_clock_divisor", UInt32(div)))
  }

  val tokenHasherControlIO = IO(new TokenHasherControlBundle())

  // only use for meta data
  val hashRecord = mutable.ArrayBuffer[TokenHasherMeta]()

  override def genCRFile(): MCRFile = {
    // ------------------------------------------------------
    // arrayBuffer (defined bridge module imp)
    // create mutable structure here to capture data about MIMO related to tokenHahers fifo / controls
    // channelName
    // direction
    // mmio address
    //   dequeue (128 deep)
    //   head
    
    // one test with at least multiple bridges
    // test both directions

    // a peek poke bridge in loopback will test token hashers in both directions

    // ------------------------------------------------------
    if(p(InsertTokenHashersKey)) {
      println("INSERT TOKEN HASHERS")
      tokenHashers2()
    } else {
      println("NOOOOOOOOOT INSERT TOKEN HASHERS")

    }

    
    super.genCRFile()
  }

  def tokenHashers2() = {

    tokenHashersDirection(hPort.getOutputChannelPorts(), true)
    tokenHashersDirection(hPort.getInputChannelPorts(), false)

    Unit  
  }

  def tokenHashersDirection(list: Seq[(String,DecoupledIO[Data])], output: Boolean) = {

    val bridgeName = name;
    list.map({ case (signalName,ch) =>

      println(s"DIRECTION ${signalName}")
      
      // how many tokens have we seen
      val tokenCount = WideCounter(width = 64, inhibit = !ch.fire).value
      
      val triggerDelay = tokenHasherControlIO.triggerDelay
      val triggerFrequency = tokenHasherControlIO.triggerPeriod


      val delayMatchMulti = triggerDelay === tokenCount
      val delayMatch = delayMatchMulti && !RegNext(delayMatchMulti)
      val triggerStart = RegInit(false.B)
      

      // true when the period counter should reset
      val periodCountReset = Wire(Bool())
      
      // counter that advances each time the channel fires
      // this is used to determine which hashes we will use
      val (periodCount: UInt, periodCountOverflow) = Counter.apply(Range(0, 2147483647, 1), enable=ch.fire, reset=periodCountReset)
      // val (periodCount: UInt, periodCountOverflow) = Counter.apply(Range(0, 2147483647, 1), enable=true.B, reset=periodCountReset)

      val periodMatch = periodCount === triggerFrequency(30, 0)

      when(delayMatch | periodMatch) {
        periodCountReset := true.B & ch.fire
      }.otherwise{
        periodCountReset := false.B
      }

      val periodOK = periodCountReset


      // only set triggerStart when the delay counter matches
      // this will stay high
      when(delayMatch) {
        triggerStart := true.B
      }



      val chFire = ch.fire
      
      val shouldHash = periodOK & triggerStart
      
      val hash = XORHash32(ch.bits, ch.fire)

      // counter instead of hash to debug 
      val counterAsHash = WideCounter(width = 32, inhibit = !ch.fire).value

      val useHash = if(p(TokenHashersUseCounter)) counterAsHash else hash

      val fifoDepth = p(TokenHashersDepth)

      // 36K bits (32K usable bits)
      val q = Module(new BRAMQueue(fifoDepth)(UInt(32.W)))
      q.io.enq.valid := shouldHash
      q.io.enq.bits := useHash

      val queueHead = attachDecoupledSource(q.io.deq, s"queueHead_${signalName}")

      val occupanyName = s"queueOccupancy_${signalName}"
      val counter0Name = s"tokenCount0_${signalName}"
      val counter1Name = s"tokenCount1_${signalName}"

      val occupancyReg = genROReg(q.io.count, occupanyName)
      val counterLow   = genROReg(tokenCount(31,0), counter0Name)
      val counterHigh  = genROReg(tokenCount(63,32), counter1Name)
      
      val meta = TokenHasherMeta(bridgeName, signalName, output, queueHead, getCRAddr(occupanyName), getCRAddr(counter0Name), getCRAddr(counter1Name))

      hashRecord += meta
    })

    Unit
  }
}

trait Bridge[HPType <: Record with HasChannels, WidgetType <: BridgeModule[HPType]] {
  self: BaseModule =>
  def constructorArg: Option[_ <: AnyRef]
  def bridgeIO: HPType
  // def bridgeIO: HPType with HasChannels

  def generateAnnotations(): Unit = {

    // Adapted from https://medium.com/@giposse/scala-reflection-d835832ed13a
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val classType = mirror.classSymbol(getClass)
    // The base class here is Bridge, but it has not yet been parameterized.
    val baseClassType = ru.typeOf[Bridge[_,_]].typeSymbol.asClass
    // Now this will be the type-parameterized form of Bridge
    val baseType = ru.internal.thisType(classType).baseType(baseClassType)
    val widgetClassSymbol = baseType.typeArgs(1).typeSymbol.asClass

    // Generate the bridge annotation
    annotate(new ChiselAnnotation { def toFirrtl = {
        BridgeAnnotation(
          self.toNamed.toTarget,
          bridgeIO.bridgeChannels,
          widgetClass = widgetClassSymbol.fullName,
          widgetConstructorKey = constructorArg)
      }
    })
  }
}

trait HasChannels {
  /**
    * Returns a list of channel descriptors.
    */
  def bridgeChannels(): Seq[BridgeChannel]

  def getOutputChannelPorts(): Seq[(String,DecoupledIO[Data])]
  def getInputChannelPorts(): Seq[(String,DecoupledIO[Data])]

  // Called in FPGATop to connect the instantiated bridge to channel ports on the wrapper
  private[midas] def connectChannels2Port(bridgeAnno: BridgeIOAnnotation, channels: TargetChannelIO): Unit
}
