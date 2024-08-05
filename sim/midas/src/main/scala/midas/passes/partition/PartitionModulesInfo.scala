package midas.passes.partition

import Array.range
import firrtl._
import firrtl.analyses.InstanceGraph
import midas._
import org.chipsalliance.cde.config.Parameters

object PartitionModulesInfo {
  val wrapperPfx      = "PartitionWrapper"
  val groupPfx        = "Grouped"
  val groupWrapperPfx = "GroupWrapper"

  val fireSimWrapper            = "FireSimPartition"
  val extractModuleInstanceName = "extractModuleInstance"

  def getConfigParams(annos: AnnotationSeq): Parameters = {
    annos
      .collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p) =>
        p
      })
      .get
  }

  var numGroups: Int = 0

  private def getNumGroups(state: CircuitState): Int = {
    state.circuit.modules.filter { dm =>
      dm.name contains groupPfx
    }.size
  }

  def getGroupName(i: Int): String = {
    s"${groupPfx}_${i}"
  }

  def getGroups(state: CircuitState): (Seq[String], Seq[String]) = {
    if (numGroups == 0) {
      numGroups = getNumGroups(state)
    }
    val groups        = range(0, numGroups).map { getGroupName(_) }
    val groupWrappers = groups.map { g => groupWrapperPfx + "_" + g }
    (groups, groupWrappers)
  }

  def getCutBridgeType(p: Parameters): String = {
    val isQSFP = p(FireAxeQSFPConnections)
    val isPCIM = p(FireAxePCIMConnections)
    val isPCIS = p(FireAxePCISConnections)

    if (isQSFP && isPCIM) assert(false)
    if (isQSFP && isPCIS) assert(false)
    if (isPCIS && isPCIM) assert(false)

    if (isQSFP) "QSFP"
    else if (isPCIM) "PCIM"
    else "PCIS"
  }
}
