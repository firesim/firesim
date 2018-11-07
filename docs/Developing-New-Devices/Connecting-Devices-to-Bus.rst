Connecting Devices to Bus
=========================

SoC Mixin Traits
----------------

Now that we have finished designing our peripheral device, we need to
hook it up into the SoC. To do this, we first need to create two traits:
one for the lazy module and one for the module implementation. The lazy
module trait is the following.

.. code-block:: scala

    trait HasPeripheryInputStream { this: BaseSubsystem =>
      private val portName = "input-stream"
      val streamWidth = pbus.beatBytes * 8
      val inputstream = LazyModule(new InputStream(0x10017000, pbus.beatBytes))
      pbus.toVariableWidthSlave(Some(portName)) { inputstream.regnode }
      sbus.fromPort(Some(portName))() := inputstream.dmanode
      ibus.fromSync := inputstream.intnode
    }

We add the line ``this: BaseSubsystem =>`` to indicate that this trait will
eventually be mixed into a class that extends ``BaseSubsystem``, which contains
the definition of the system bus ``sbus``, peripheral bus ``pbus``, and
interrupt bus ``ibus``. We instantiate the ``InputStream`` lazy module and
give it the base address ``0x10017000``. We connect the ``pbus`` into the
register node, DMA node to the ``sbus``, and interrupt node to the ``ibus``.

The module implementation trait is as follows:

.. code-block:: scala

    class FixedInputStream(data: Seq[BigInt], w: Int) extends Module {
        val io = IO(new Bundle {
            val out = Decoupled(UInt(w.W))
        })

        val dataVec = VecInit(data.map(_.U(w.W)))
        val (dataIdx, dataDone) = Counter(io.out.fire(), data.length)
        val sending = RegInit(true.B)

        io.out.valid := sending
        io.out.bits := dataVec(dataIdx)

        when (dataDone) { sending := false.B }
    }

    trait HasPeripheryInputStreamModuleImp extends LazyModuleImp {
      val outer: HasPeripheryInputStream

      val stream_in = IO(Flipped(Decoupled(UInt(outer.streamWidth.W))))
      outer.inputstream.module.io.in <> stream_in

      def connectFixedInput(data: Seq[BigInt]) {
        val fixed = Module(new FixedInputStream(data, outer.streamWidth))
        stream_in <> fixed.io.out
      }
    }

Since the interrupts and memory ports have already been connected in the
lazy module trait, the module implementation trait only needs to create the
external decoupled interface and connect that to the ``InputStream`` module
implementation.

The ``connectFixedInput`` method will be used by the test harness to connect
an input stream model that just sends a pre-specified stream of data.

Top-Level Design and Configuration
----------------------------------

We can now mix these traits into the SoC design. Open up
``src/main/scala/example/Top.scala`` and add the following:

.. code-block:: scala

    class ExampleTopWithInputStream(implicit p: Parameters) extends ExampleTop
        with HasPeripheryInputStream {
      override lazy val module = new ExampleTopWithInputStreamModule(this)
    }

    class ExampleTopWithInputStreamModule(outer: ExampleTopWithInputStream)
      extends ExampleTopModuleImp(outer)
      with HasPeripheryInputStreamModuleImp


We can then build a simulation using our new SoC by adding a configuration
to ``src/main/scala/example/Configs.scala``. This configuration will cause
the test harness to instantiate an SoC with the ``InputStream`` device
and then connect a fixed input stream model to it.

.. code-block:: scala

    class WithFixedInputStream extends Config((site, here, up) => {
      case BuildTop => (clock: Clock, reset: Bool, p: Parameters) => {
        val top = Module(LazyModule(new ExampleTopWithInputStream()(p)).module)
        top.connectFixedInput(Seq(
          BigInt("1002abcd", 16),
          BigInt("34510204", 16),
          BigInt("10329999", 16),
          BigInt("92101222", 16)))
        top
      }
    })

    class FixedInputStreamConfig extends Config(
      new WithFixedInputStream ++ new BaseExampleConfig)

We can now compile the simulation using VCS.

.. code-block:: shell

    cd vsim
    make CONFIG=FixedInputStreamConfig

This will produce a ``simv-example-FixedInputStreamConfig`` executable that
can be used to run tests. We will discuss how to write and run those tests in
the next section.

If you don't have VCS installed and want to use
verilator instead, the commands are similar.

.. code-block:: shell

    cd verisim
    make CONFIG=FixedInputStreamConfig

This creates an executable called ``simulator-example-FixedInputStreamConfig``.
