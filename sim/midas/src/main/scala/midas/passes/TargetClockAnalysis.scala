// See LICENSE for license details.

package midas.passes

import midas.passes.fame.{FAMEChannelConnectionAnnotation, TargetClockChannel}
import midas.widgets.{RationalClock}

import firrtl._
import firrtl.annotations._


/**
  *  [[ChannelClockInfoAnalysis]]'s output annotation. Maps channel global name
  *  (See [[FAMEChannelConnectionAnnotation]] to a clock info class.
  */
case class ChannelClockInfoAnnotation(infoMap: Map[String, RationalClock]) extends NoTargetAnnotation

/**
  *  Returns a map from a channel's global name to a RationalClock case class which
  *  contains metadata about the target clock including its name and relative
  *  frequency to the base clock
  *
  */
object ChannelClockInfoAnalysis extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  def analyze(state: CircuitState): Map[String, RationalClock] = {
    val clockChannels = state.annotations.collect {
      case FAMEChannelConnectionAnnotation(_,TargetClockChannel(clocks,_),_,_,Some(clockRTs)) =>
        clockRTs zip clocks
    }
    require(clockChannels.size == 1,
      s"Expected exactly one clock channel annotation. Got: ${clockChannels.size}")
    val sourceInfoMap = clockChannels.head.toMap

    // This relies on the assumption that the clock channel will not have its clock field set
    val channelClocks = state.annotations.collect({
      case FAMEChannelConnectionAnnotation(name,_,Some(clock),_,_) => (name, clock)
    }).toMap

    val finder = new ClockSourceFinder(state)
    val clockSourceMap = channelClocks.map({ case (k, v) => v -> finder.findRootDriver(v) }).toMap
    channelClocks.map { case (channelName, sinkClock) =>
      val source = clockSourceMap(sinkClock).getOrElse(throw new Exception(
        s"""|Could not find source clock for channel ${channelName}.
            |It is either unconnected or there is an intermediate operation (e.g., a clock gate
            |or clock mux) between the channel's clock and the clock bridge generated clock.""".stripMargin))

      (channelName, sourceInfoMap(source))
    }.toMap
  }
  def execute(state: CircuitState): CircuitState = {
    val infoAnno = ChannelClockInfoAnnotation(analyze(state))
    state.copy(annotations = infoAnno +: state.annotations)
  }
}
