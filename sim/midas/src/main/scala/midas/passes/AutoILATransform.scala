//See LICENSE for license details

package midas.passes

import midas.{EnableAutoILA, ILADepthKey, ILAProbeTriggersKey}
import midas.targetutils.FirrtlFpgaDebugAnnotation
import midas.stage.GoldenGateOutputFileAnnotation
import midas.stage.phases.ConfigParametersAnnotation

import firrtl._
import firrtl.Mappers._
import firrtl.ir._
import firrtl.passes._
import firrtl.annotations._
import firrtl.stage.Forms
import firrtl.transforms.BlackBoxInlineAnno
import firrtl.transforms.TopWiring._

/** Finds all FPGADebug annotations deployed throughout the circuit, and wires them to a single ILA instance at the top
  * of the module hierarchy. The ILA is wrapped in a shim module that preserves the marked signal names and instance
  * path names in its port list. The shim module instantiates the Xilinx IP only when `SYNTHESIS is defined.
  *
  * This emits an annotation pass generates annotations for two files:
  *   - A ip generation script (tcl) for the ILA IP
  *   - A verilog implementation for the ILA shim. This is verbatim verilog so as to permit using verilog preprocessor
  *     macros.
  *
  * Caveats:
  *   - Targets labelled with the FPGADebug annotation must be synchronous to the simulator's host clock. In practice,
  *     the PlatformShim is the only location where it is possible to violate this assumption.
  */
object AutoILATransform extends Transform with DependencyAPIMigration {
  override def name = "[FireSim] AutoILA Wiring Transform"

  type InstPath = Seq[String]

  override def prerequisites          = Forms.MidForm
  override def optionalPrerequisites  = Seq.empty
  // We want this pass to run before any emitters
  override def optionalPrerequisiteOf = Forms.HighEmitters

  override def invalidates(a: Transform): Boolean = a match {
    case InferTypes | ResolveKinds | ResolveFlows | ExpandConnects => true
    case _                                                         => false
  }

  /** Generates the filename for the ila wrapper black box.
    *
    * Can't easily emit verilog black boxes using Golden Gate's file emission system: If using a BBPath anno: the path
    * to verilog may not exist when the BlackBoxSourceHelper runs If using a BBInline anno: the BlackBoxSource helper
    * will write the file out itself. So, use the BBInline anno, and let firrtl do the file emission, but look up Golden
    * Gate's OutputBaseFilename to be consistent.
    *
    * @param annotations
    * @return
    *   The filename of the wrapper
    */
  def ilaWrapperFilename(annotations: Seq[Annotation]): String = {
    val ilaWrapperSuffix = ".ila_wrapper_inst.v"
    val filePrefix       = annotations.collectFirst({ case midas.stage.OutputBaseFilenameAnnotation(p) => p }).get
    filePrefix + ilaWrapperSuffix
  }

  /** Captures per-probe metadata used to build various files related to ILA generation and instantiation
    *
    * @param index
    *   index of the probe on the ILA.
    * @param width
    *   bit width of the probe
    * @param name
    *   verilog-compatible string that will be used in the wrapper definition and describes the underlying signal being
    *   probed.
    * @param probeTriggers
    *   specifies the number of comparators to be generated for this probe. This will be ignored if
    *   CONFIG.ALL_PROBE_SAME_MU_CNT is set.
    */
  case class ILAProbe(
    index:         Int,
    width:         Int,
    name:          String,
    probeTriggers: Int,
  ) {

    /** A string that configures the ILA IP elaboration script for this probe */
    def ipConfigString =
      s"CONFIG.C_PROBE${index}_WIDTH {$width}  CONFIG.C_PROBE${index}_MU_CNT {$probeTriggers}"

    /** A verilog input definition on the generated wrapper module */
    def wrapperPortDef =
      s"input [${width - 1}:0] ${name}"

    /** A connection from wrapper port to the ILA instance */
    def ilaPortAssignment =
      s".probe${index} (${name})"
  }

  private def ilaLog(msg: String): Unit =
    println("Auto ILA: " + msg)

  private def runTransform(
    state:         CircuitState,
    ilaAnnos:      Seq[FirrtlFpgaDebugAnnotation],
    dataDepth:     Int,
    probeTriggers: Int,
  ): CircuitState = {

    // Map debug annotations to top wiring annotations
    val targetAnnos = ilaAnnos match {
      case p => p.map { case FirrtlFpgaDebugAnnotation(target) => TopWiringAnnotation(target, s"ila_") }
    }

    // Sneak out mapping, which has information about port widths needed for
    // the ILA Tcl file, from a "dummy" TopWiringOutputFilesAnnotation Code for
    // this taken from
    // https://github.com/firesim/firesim/blob/master/sim/midas/src/main/scala/midas/passes/BridgeTopWiring.scala
    var extractedMappings = Seq[((ComponentName, Type, Boolean, InstPath, String), Int)]()

    def wiringAnnoOutputFunc(
      td:       String,
      mappings: Seq[((ComponentName, Type, Boolean, Seq[String], String), Int)],
      state:    CircuitState,
    ): CircuitState = {
      extractedMappings = mappings
      state
    }

    val topWiringAnnos = TopWiringOutputFilesAnnotation("unused", wiringAnnoOutputFunc) +: targetAnnos

    val c                = state.circuit
    val circuitNamespace = Namespace(c)
    val oldTop           = c.modules.collectFirst({ case m: Module if m.name == c.main => m }).get

    val wiredState                 = (new TopWiringTransform)
      .execute(state.copy(annotations = topWiringAnnos ++ state.annotations))

    val (possibleTops, submodules) = wiredState.circuit.modules.partition {
      case m: Module if m.name == c.main => true
      case o                             => false
    }

    val newTop             = possibleTops.head.asInstanceOf[Module]
    val newPorts           = newTop.ports.filter(!oldTop.ports.contains(_))
    val newPortNames       = newPorts.map(_.name)
    val newTopWithOldPorts = newTop.copy(ports = oldTop.ports)
    val newTopNamespace    = Namespace(newTop)

    // Old top-level ports were outputs. To blackboxed ILA, they will be inputs. Flip the old ports.
    def flipPort(p: Port): Port = p match {
      case Port(_, _, Input, _)  => p.copy(direction = Output)
      case Port(_, _, Output, _) => p.copy(direction = Input)
    }
    val flippedPorts = newPorts.map(flipPort).toSeq
    val probes = extractedMappings.map { case ((cname, tpe, _, path, prefix), index) =>
      val probeWidth = tpe match { case GroundType(IntWidth(w)) => w.toInt }
      ILAProbe(index, probeWidth, prefix + path.mkString("_"), probeTriggers)
    }

    ilaLog(s"Sample depth = ${dataDepth}, trigger comparators = ${probeTriggers}")
    ilaLog(s"Generated ${probes.size} probes:")
    for (probe <- probes) {
      println(s"  ${probe.name}, width: ${probe.width}")
    }
    // IP elaboration script.
    val ilaBlackBoxName       = circuitNamespace.newName("ila_firesim")
    val fullConfigString      = probes.map(_.ipConfigString).mkString(" \\\n  ")
    val ipElaborationFileAnno = GoldenGateOutputFileAnnotation(
      s"""|create_ip -name ila \\
          |  -vendor xilinx.com \\
          |  -library ip \\
          |  -version  6.2 \\
          |  -module_name ${ilaBlackBoxName}
          |set_property -dict [list \\
          |  ${fullConfigString} \\
          |  CONFIG.C_NUM_OF_PROBES {${probes.size}} \\
          |  CONFIG.C_DATA_DEPTH {$dataDepth} \\
          |  CONFIG.C_TRIGOUT_EN {false} \\
          |  CONFIG.C_EN_STRG_QUAL {1} \\
          |  CONFIG.C_ADV_TRIGGER {true} \\
          |  CONFIG.C_TRIGIN_EN {false} \\
          |  CONFIG.ALL_PROBE_SAME_MU_CNT {$probeTriggers}] [get_ips ${ilaBlackBoxName}]
          |""".stripMargin,
      s".${ilaBlackBoxName}.ipgen.tcl",
    )

    val ilaWrapperModuleName = circuitNamespace.newName("ila_wrapper")
    val ilaWrapperInstName   = newTopNamespace.newName("ila_wrapper_inst")
    val ilaWrapperInst       = DefInstance(NoInfo, ilaWrapperInstName, ilaWrapperModuleName)
    val ilaWrapperMT         = ModuleTarget(c.main, ilaWrapperModuleName)

    val ilaWrapperPortNS    = Namespace(flippedPorts.map(_.name))
    val ilaWrapperClockName = ilaWrapperPortNS.newName("clock")
    val ilaWrapperClockPort = Port(NoInfo, ilaWrapperClockName, Input, ClockType)

    // Emit the wrapper as a black box since we can't express Verilog ifdefs in IR
    val ilaWrapper = ExtModule(
      info    = NoInfo,
      name    = ilaWrapperModuleName,
      ports   = ilaWrapperClockPort +: flippedPorts,
      defname = ilaWrapperModuleName,
      params  = Nil,
    )

    val ilaWrapperPortList =
      (s"input ${ilaWrapperClockName}" +: probes.map(_.wrapperPortDef)).mkString(",\n")

    val ilaPortAssignments =
      (s".clk(${ilaWrapperClockName})" +: probes.map(_.ilaPortAssignment)).mkString(",\n    ")

    val ilaWrapperBody =
      s"""|// A wrapper module around the ILA IP instance. This serves two purposes:
          |// 1. It gives the probes reasonable names in the GUI
          |// 2. Verilog ifdefs the remove the ILA instantiation in metasimulation.
          |module ${ilaWrapperModuleName} (
          |    ${ilaWrapperPortList}
          |);
          |// Don't instantiate the ILA when running under metasimulation
          |`ifdef SYNTHESIS
          |  ${ilaBlackBoxName} CL_FIRESIM_DEBUG_WIRING_TRANSFORM (
          |    ${ilaPortAssignments}
          |  );
          |`endif
          |endmodule
          |""".stripMargin

    val ilaWrapperBBAnno =
      BlackBoxInlineAnno(ilaWrapperMT.toNamed, ilaWrapperFilename(state.annotations), ilaWrapperBody)

    // Re-target connect statements, which now reference the removed ports, to ILA wrapper
    def rewireConnects(s: Statement): Statement = s.map(rewireConnects) match {
      case c @ Connect(_, WRef(portName, _, _, _), rhs) if newPortNames.contains(portName) =>
        c.copy(loc = SubField(WRef(ilaWrapperInstName), portName))
      case o                                                                               => o
    }
    val rewiredTop = newTopWithOldPorts.copy(
      body = Block(ilaWrapperInst, newTopWithOldPorts.body.map(rewireConnects))
    )
    val newCircuit = state.circuit.copy(modules = Seq(rewiredTop, ilaWrapper) ++ submodules)

    // Produce a reference to the ILA clock, so HostClockWiring can drive the
    // clock port of the ILA wrapper
    val ilaClockAnnotation = HostClockSink(
      ModuleTarget(c.main, c.main)
        .ref(ilaWrapperInstName)
        .field(ilaWrapperClockName)
    )

    HostClockWiring(
      state.copy(
        circuit     = newCircuit,
        annotations = Seq(ipElaborationFileAnno, ilaWrapperBBAnno, ilaClockAnnotation) ++ wiredState.annotations,
      )
    )
  }

  def execute(state: CircuitState): CircuitState = {
    val ilaAnnos      = state.annotations.collect { case a: FirrtlFpgaDebugAnnotation => a }
    val p             = state.annotations.collectFirst({ case ConfigParametersAnnotation(p) => p }).get
    val dataDepth     = p(ILADepthKey)
    val probeTriggers = p(ILAProbeTriggersKey)

    val newState = if (!p(EnableAutoILA)) {
      ilaLog("p(EnableAutoILA) unset. Skipping AutoILATransform")
      state
    } else if (ilaAnnos.isEmpty) {
      ilaLog("No FPGADebug annotations found. Skipping AutoILATransform")
      state
    } else {
      runTransform(state, ilaAnnos, dataDepth, probeTriggers)
    }

    val cleanedAnnos = newState.annotations.filter {
      case a: TopWiringAnnotation            => false
      case a: TopWiringOutputFilesAnnotation => false
      case a: FirrtlFpgaDebugAnnotation      => false
      case _                                 => true
    }
    newState.copy(annotations = cleanedAnnos)
  }
}
