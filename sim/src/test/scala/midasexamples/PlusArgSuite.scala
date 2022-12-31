// See LICENSE for license details.

package firesim.midasexamples

/** Trait so that we have a uniform numbering scheme for the plusargs tests
  */
trait PlusArgsKey {
  def getKey(groupNumber: Int, testNumber: Int): String = {
    val key = (groupNumber << 4 | testNumber)
    s"+plusargs_test_key=${key}"
  }
}

class PlusArgsGroup68Bit
    extends TutorialSuite("PlusArgsModule", "PlusArgsModuleTestConfigGroup68Bit")
    with PlusArgsKey {
  it should "provide the correct default value, 3 slice" in {
    assert(run("verilator", false, args = Seq(getKey(0, 0))) == 0)
  }

  it should "accept an int from the command line" in {
    assert(run("verilator", false, args = Seq(s"+plusar_v=3", getKey(0, 1))) == 0)
    assert(run("verilator", false, args = Seq(s"+plusar_v=${BigInt("f00000000", 16)}", getKey(0, 2))) == 0)
    assert(run("verilator", false, args = Seq(s"+plusar_v=${BigInt("f0000000000000000", 16)}", getKey(0, 3))) == 0)
  }

  it should "reject large runtime values" in {
    assert(run("verilator", false, args = Seq(s"+plusar_v=${BigInt("ff0000000000000000", 16)}", getKey(0, 4))) != 0)
  }
}

class PlusArgsGroup29Bit
    extends TutorialSuite("PlusArgsModule", "PlusArgsModuleTestConfigGroup29Bit")
    with PlusArgsKey {
  it should "provide the correct default value, 1 slice" in {
    assert(run("verilator", false, args = Seq(getKey(1, 0))) == 0)
  }

  it should "accept an int from the command line, 1 slice" in {
    assert(run("verilator", false, args = Seq(s"+plusar_v=${BigInt("1eadbeef", 16)}", getKey(1, 1))) == 0)
  }
}
