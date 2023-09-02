// See LICENSE

package midas.chiselstage

import firrtl.AnnotationSeq
import firrtl.options.OptionsView

package object stage {

  implicit object MidasOptionsView extends OptionsView[MidasOptions] {

    def view(annotations: AnnotationSeq): MidasOptions = annotations
      .collect { case a: MidasOption => a }
      .foldLeft(new MidasOptions()) { (c, x) =>
        x match {
          case TopModuleAnnotation(a)      => c.copy(topModule = Some(a))
          case ConfigsAnnotation(a)        => c.copy(configNames = Some(a))
          case OutputBaseNameAnnotation(a) => c.copy(outputBaseName = Some(a))
        }
      }

  }

}
