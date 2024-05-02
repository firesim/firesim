package midas

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.WrappedExpression._

import annotations._
import firrtl.Utils.BoolType
import firrtl.transforms.BlackBoxInlineAnno



package object passes {
  /**
    * A utility for keeping statements defining and connecting signals to a piece of hardware
    * together with a reference to the component. This is useful for passes that insert hardware,
    * since the "collateral" of that object can be kept in one place.
    */
  case class SignalInfo(decl: Statement, assigns: Statement, ref: Expression)

  /**
    * A utility for creating a wire that "echoes" the value of an existing expression.
    */
  object PassThru {
    def apply(source: WRef)(implicit ns: Namespace): SignalInfo = apply(source, source.name)
    def apply(source: WRef, suggestedName: String)(implicit ns: Namespace): SignalInfo = {
      val decl = DefWire(NoInfo, ns.newName(suggestedName), source.tpe)
      val ref = WRef(decl)
      SignalInfo(decl, Connect(NoInfo, WRef(decl), source), ref)
    }
  }

  /**
    * This pass ensures that the AbstractClockGate blackbox is defined in a circuit, so that it can
    * later be instantiated. The blackbox clock gate has the following signature:
    * 
    * module AbstractClockGate(input I, input CE, output O);
    * 
    * I and O are the input and output clocks, respectively, while CE is the enable signal.
    */
  object DefineAbstractClockGate extends Transform with FunctionalPass[CircuitName] {
    val blackboxName = "AbstractClockGate"
    val blackbox = ExtModule(
      info = NoInfo,
      name = blackboxName,
      ports =  Seq(
        Port(NoInfo, "I", Input, ClockType),
        Port(NoInfo, "CE", Input, BoolType),
        Port(NoInfo, "O", Output, ClockType)),
      defname = blackboxName,
      params = Nil)

    val impl =
      s"""|`ifndef ${blackboxName.toUpperCase()}
          |`define ${blackboxName.toUpperCase()}
          |module ${blackboxName}(
          |  input      I,
          |  input      CE,
          |  output reg O
          |);
          |  /* verilator lint_off COMBDLY */
          |  reg enable;
          |  always @(posedge I)
          |    enable <= CE;
          |  assign O = (I & enable);
          |  /* verilator lint_on COMBDLY */
          |endmodule
          |`endif
          |""".stripMargin

    def analyze(cs: CircuitState): CircuitName = CircuitName(cs.circuit.main)

    def transformer(cName: CircuitName) = {
      c: Circuit =>
        c.copy(modules = c.modules ++ Some(blackbox).filterNot(bb => c.modules.contains(blackbox)))
    }

    def annotater(cName: CircuitName) = {
      anns: AnnotationSeq =>
        val blackboxAnn = BlackBoxInlineAnno(ModuleName(blackboxName, cName), s"${blackboxName}.v", impl)
        anns ++ Some(blackboxAnn).filterNot(a => anns.contains(blackboxAnn))
    }
  }

  /**
    * Adds a default to a partial function.
    */
  object OrElseIdentity {
    /**
      * Transforms a partial function with matching input and output types into a total function. In
      * cases where the partial function is not defined, the identity function is used.
      * 
      * @tparam T The type for both the input and output of the partial function
      * @param f The partial function to transform
      * @return Returns a total function (T) => T
      */
    def apply[T](f: PartialFunction[T, T]): T => T = {
      f.orElse({ case x => x }: PartialFunction[T, T])
    }
  }

  /**
    * Generates a function transforming a circuit from a partial function describing how each
    * module, if matched, is transformed.
    */
  object ModuleTransformer {
    /**
      * @param f A partial function that transforms matched modules
      * @return Returns a function that applies the input function to each module in the circuit
      */
    def apply(f: PartialFunction[DefModule, DefModule]): Circuit => Circuit = {
      c => c mapModule OrElseIdentity(f)
    }
  }

  /**
    * Generates a function transforming a circuit from a partial function describing how each
    * statement, if matched, is transformed. This is applied recursively to all statements in the
    * circuit.
    */
  object StatementTransformer {
    /**
      * @param f A partial function that transforms matched statements
      * @return Returns a function that recursively applies the input function to each statement in the circuit
      */
    def apply(f: PartialFunction[Statement, Statement]): Circuit => Circuit = {
      val fTotal = OrElseIdentity(f)
      ModuleTransformer { case m => m mapStmt { s => fTotal(s mapStmt fTotal) } }
    }
  }

  /**
    * Generates a function transforming a circuit from a partial function describing how each
    * expression, if matched, is transformed. This is applied recursively to all expressions in the
    * circuit.
    */
  object ExpressionTransformer {
    /**
      * @param f A partial function that transforms matched expressions
      * @return Returns a function that recursively applies the input function to each expression in the circuit
      */
    def apply(f: PartialFunction[Expression, Expression]): Circuit => Circuit = {
      val fTotal = OrElseIdentity(f)
      StatementTransformer { case s => s mapExpr { e => fTotal(e mapExpr fTotal) } }
    }
  }

  /**
    * A utility for matching and replacing FIRRTL expression trees
    */
  object ReplaceExpression {
    private def onExpr(repls: Map[WrappedExpression, Expression])(e: Expression): Expression = repls.getOrElse(we(e), e map onExpr(repls))
    /**
      * Recursively replace expressions in a Statement tree according to a map. Since the keys are
      * of type WrappedExpression, the matching is based on "WrappedExpression equality," which
      * ignores metadata that may be present in the nodes, like info, type, or kind.
      * 
      * @param repls A map defining how each expression (in wrapped form) is replaced by a matching value, if any
      * @param s The input statement to transform
      * @return Returns a statement tree transformed by substitution according to the replacement map
      */
    def apply(repls: Map[WrappedExpression, Expression])(s: Statement): Statement = s map apply(repls) map onExpr(repls)
  }

  /**
    * A pass that is described as a chain of pure function calls.
    * 
    * @tparam T The type of the analysis object returned by the analysis phase.
    */
  trait FunctionalPass[T] {
    def inputForm: CircuitForm = UnknownForm
    def outputForm: CircuitForm = UnknownForm
    def updateForm(i: CircuitForm): CircuitForm = outputForm match {
      case UnknownForm => i
      case _ => outputForm
    }

    /**
      * Examine the circuit and store information in an analysis object
      */
    def analyze(cs: CircuitState): T

    /**
      * @param analysis The results of the analysis pass
      * @return Returns a function that, when called, transforms the circuit
      */
    def transformer(analysis: T): Circuit => Circuit

    /**
      * @param analysis The results of the analysis pass
      * @return Returns a function that, when called, transformes the annotations
      */
    def annotater(analysis: T): AnnotationSeq => AnnotationSeq

    /**
      * @param analysis The results of the analysis pass
      * @return Returns a RenameMap that, when applied, appropriately transforms targets
      */
    def renamer(analysis: T): Option[RenameMap] = None

    final def execute(input: CircuitState): CircuitState = {
      val analysis = analyze(input)
      val outputCircuit = transformer(analysis)(input.circuit)
      val outputAnnos = annotater(analysis)(input.annotations)
      CircuitState(outputCircuit, updateForm(input.form), outputAnnos, renamer(analysis))
    }
  }

  /**
    * A pass that simply applies a (Circuit) => Circuit function.
    */
  trait NoAnalysisPass extends FunctionalPass[Unit] {
    final def analyze(cs: CircuitState): Unit = ()
    final def transformer(analysis: Unit): Circuit => Circuit = transformer
    final def annotater(analysis: Unit): AnnotationSeq => AnnotationSeq = annotater
    final override def renamer(analysis: Unit): Option[RenameMap] = renamer

    val transformer: Circuit => Circuit
    val annotater: AnnotationSeq => AnnotationSeq = identity[AnnotationSeq](_)
    def renamer: Option[RenameMap] = None
  }

}
