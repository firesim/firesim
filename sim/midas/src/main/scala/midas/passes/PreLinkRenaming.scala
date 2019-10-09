// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.annotations._
import firrtl.ir._
import firrtl.Mappers._
import Utils._
import java.io.{File, FileWriter, StringWriter}

// This transform renames a wrapper circuit's modules and annotations in
// preparation for linking (eg. PlatformMapping). Subsumes Utils.renameMods
// We want to preserve the module names of the circuit we are wrapping (the child),
// so we reference its namespace to generate new names.
private[passes] class PreLinkRenaming(childNamespace: Namespace) extends firrtl.Transform {

  override def name = "[MIDAS] Pre-link Module Renaming"
  // TODO: Technically this xform should be able to accept AnyForm(TM); and be form idempotent
  def inputForm = HighForm
  def outputForm = HighForm

  // Updates instantiations of modules that would alias
  def updateInsts(nameMap: Map[String, String])(s: Statement): Statement = s match {
    case s: WDefInstance => s.copy(module = nameMap(s.module))
    case s => s.map(updateInsts(nameMap))
  }
  // Renames the module if there is a collision, and updates submodule inst names
  def updateModNames(nameMap: Map[String, String])(m: DefModule): DefModule = (m match {
      case m : ExtModule => m.copy(name = nameMap(m.name))
      case m :    Module => m.copy(name = nameMap(m.name))
  }) map updateInsts(nameMap)

  def execute(state: CircuitState): CircuitState = {
    val circuit = state.circuit
    require(!childNamespace.contains(circuit.main), "Submodule in child has same name as parent's top")

    // Generate new names for all modules of our circuit -- if the original name
    // is not present in the child's namespace, it is preserved
    val namePairs = circuit.modules.map(m => m.name -> childNamespace.newName(m.name))

    // Generate a rename map to update annotations that reference de-aliased modules
    val cname = CircuitName(circuit.main)
    val renameMap = RenameMap(namePairs.map({
      case (from, to) => ModuleName(from,cname) -> Seq(ModuleName(to,cname))
    }).toMap)

    renameMap.setCircuit(circuit.main)

    state.copy(circuit = circuit.copy(modules = circuit.modules map updateModNames(namePairs.toMap)),
               renames = Some(renameMap))
  }
}

// A simpler version of the above
class SingleModulePrelinkRename(newCName: String, newMName: String) extends firrtl.Transform {
  def inputForm = HighForm
  def outputForm = HighForm
  override def name = s"[MIDAS 2.0] SingleModulePrelinkRename"

  def execute(state: CircuitState) = {
    val oldName = state.circuit.main
    state.copy(
      circuit = state.circuit.copy(
        main = newCName,
        modules = state.circuit.modules.map({
          case m: ExtModule if m.name == oldName => m.copy(name = newMName)
          case m: Module    if m.name == oldName => m.copy(name = newMName)
        })),
      renames = Some(RenameMap.create(Map(
        ModuleTarget(oldName, oldName) -> Seq(ModuleTarget(newCName, newMName))))))
  }
}

