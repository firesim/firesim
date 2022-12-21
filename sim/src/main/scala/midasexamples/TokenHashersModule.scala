//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util._
import chisel3.util.Enum
import freechips.rocketchip.config.{Config, Field, Parameters}

import midas.widgets._

import midas.widgets.ResetPulseBridge

/** Defines a test group with Token Hashers enabled. This piggy-backs on the PlusArgsTest, so PlusArgsTestNumberKey is
  * also included
  */
class EnableTokenHashersDefault
    extends Config((site, here, up) => {
      case InsertTokenHashersKey  => true
      case TokenHashersUseCounter => false
      case PlusArgsTestNumberKey  => 1
    })

/** Defines a test group with Token Hashers enabled, but in counter mode.
  */
class EnableTokenHashersCounter
    extends Config((site, here, up) => {
      case InsertTokenHashersKey  => true
      case TokenHashersUseCounter => true
      case PlusArgsTestNumberKey  => 1
    })

// Just copy this
class TokenHashersModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new PlusArgsDUT)
