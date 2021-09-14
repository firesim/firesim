// See LICENSE for license details.

package goldengate.tests

import midas.passes.fame._
import midas.widgets.RationalClock

import firrtl.annotations.{NoTargetAnnotation, JsonProtocol, InvalidAnnotationJSONException, HasSerializationHints, Annotation}
import org.json4s._
import org.scalatest.flatspec.AnyFlatSpec

class FAMEAnnotationSerialization extends AnyFlatSpec {
  def serializeAndDeserialize(anno: Annotation): Annotation = {
    val serializedAnno = JsonProtocol.serialize(Seq(anno))
    JsonProtocol.deserialize(serializedAnno).head
  }

  val baseFCCA = FAMEChannelConnectionAnnotation(
    globalName = "test",
    channelInfo = PipeChannel(1),
    clock = None,
    sources = None,
    sinks = None)

  "PipeChannel FCCAs" should "serialize and deserialize correctly" in {
    val deserAnno = serializeAndDeserialize(baseFCCA)
    assert(baseFCCA == deserAnno)
  }

  "ClockChannel FCCAs" should "serialize and deserialize correctly" in {
    val clockInfo = TargetClockChannel(Seq(RationalClock("test",1,2)), Seq(1))
    val anno = baseFCCA.copy(channelInfo = clockInfo)
    val deserAnno = serializeAndDeserialize(anno)
    assert(anno == deserAnno)
  }

  "DecoupledRevChannel FCCAs" should "serialize and deserialize correctly" in {
    val anno = baseFCCA.copy(channelInfo = DecoupledReverseChannel)
    val deserAnno = serializeAndDeserialize(anno)
    assert(anno == deserAnno)
  }
}
