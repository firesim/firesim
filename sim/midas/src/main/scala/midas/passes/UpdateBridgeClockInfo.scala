// See LICENSE for license details.

package midas.passes

import midas.widgets.{RationalClock, BridgeIOAnnotation}

import firrtl._
import firrtl.annotations._

/**
  * Determines which clock each bridge is synchronous with, and updates that bridge's IO annotation
  * to include it's domain clock info.
  *
  */
object UpdateBridgeClockInfo extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  def execute(state: CircuitState): CircuitState = {
    val infoMaps = state.annotations.collect {
      case ChannelClockInfoAnnotation(map) => map
    }
    require(infoMaps.size == 1,
      s"Expected exactly one ChannelClockInfoAnnotation. Got: ${infoMaps.size}")
    val infoMap = infoMaps.head
    val annosx =  state.annotations.map({
      case a@BridgeIOAnnotation(_,_,None,_,_,_) =>
        // There will be some cases where this is left unpopulated, i.e., for the clockBridge
        a.copy(clockInfo = infoMap.get(a.channelMapping.values.head))
      case o => o
    })
    state.copy(annotations = annosx)
  }
}
