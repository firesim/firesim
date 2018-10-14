// See LICENSE for license details.

package midas.passes

import chisel3._
import chisel3.util._

import firrtl._
import firrtl.annotations.{CircuitName, ModuleName}
import firrtl.ir._
import firrtl.Mappers._
import firrtl.transforms.fame.FAMETransformAnnotation
import Utils._
import java.io.{File, FileWriter, StringWriter}

private[passes] class ChannelizeTargetIO(io: Seq[chisel3.Data]) extends firrtl.Transform {

  override def name = "[MIDAS] ChannelizeTargetIO"
  def inputForm = LowForm
  def outputForm = LowForm

  def execute(state: CircuitState): CircuitState = {
    val circuit = state.circuit

    val moduleMap = circuit.modules.map(m => m.name -> m).toMap
    val topModule = moduleMap(circuit.main)

    def prefixWith(prefix: String, base: Any): String =
      if (prefix != "")  s"${prefix}_${base}" else base.toString

    def tokenizePort(name: String, field: Data, channelName: Option[String] = None): Seq[(String, String)] = field match {
      case c: Clock => Seq.empty
      case rv: ReadyValidIO[_] =>
        val fwdChName = prefixWith(name, "fwd")
        Seq(prefixWith(name, "valid") -> fwdChName) ++
        tokenizePort(prefixWith(name, "ready"), rv.ready, Some(prefixWith(name, "rev"))) ++
        tokenizePort(prefixWith(name, "bits"), rv.bits, Some(fwdChName))
      case b: Record =>
        b.elements.toSeq flatMap {case (n, e) => tokenizePort(prefixWith(name, n), e, channelName)}
      case v: Vec[_] =>
        v.zipWithIndex flatMap {case (e, i) => tokenizePort(prefixWith(name, i), e)}
      case b: Element => Seq(name -> channelName.getOrElse(name))
      case _ => throw new RuntimeException("Don't know how to tokenize this type")
    }

    val portMap = io.flatMap(p => tokenizePort(p.instanceName, p)).toMap
    val cname = CircuitName(circuit.main)
    val f1Anno = FAMETransformAnnotation(ModuleName(circuit.main, cname), portMap)
    println(f1Anno)

    state.copy(annotations = state.annotations ++ Seq(f1Anno))
  }
}
