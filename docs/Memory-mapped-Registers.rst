Memory-mapped Registers
=======================

In this tutorial, we will create a device which pulls in data from an
externally-connected input stream and writes the data to memory. We'll create
our device in the file ``src/main/scala/example/InputStream.scala``. The first
thing we need to do is set up some memory-mapped control registers that the CPU
can use to communicate with the device. The easiest way to do this is by
creating a ``TLRegisterNode``, which provides a ``regmap`` method that can be
used to generate the hardware for reading and writing to RTL registers.

.. code-block:: scala

    class InputStream(
        address: BigInt,
        val beatBytes: Int = 8)
        (implicit p: Parameters) extends LazyModule {
    
      val device = new SimpleDevice("input-stream", Seq("example,input-stream"))
      val regnode = TLRegisterNode(
        address = Seq(AddressSet(address, 0x3f)),
        device = device,
        beatBytes = beatBytes)
    
      lazy val module = new InputStreamModuleImp(this)
    }

We want to specify or override three arguments in the ``TLRegisterNode`` 
constructor. The first is the address of the device in the memory map.
The address is specified as an ``AddressSet`` containing two values, a base
address and a mask. The system bus will route all addresses that match the
base address on the bits not set in the mask. In this case, we set the
mask to ``0x3f``, which sets the lower six bits. This means that a 64 byte
region starting from the base address will be routed to this device.

The second argument to ``TLRegisterNode`` is a ``SimpleDevice`` object, which
provides the name and compatibility of the device table entry that will be
created for the peripheral. We won't show how this is used in this tutorial,
but it will be important if you want to create a Linux kernel driver for
the device.

The third argument to ``TLRegisterNode`` is ``beatBytes``, which specifies
the width of the TileLink interface. We will just pass this through from a
class argument.

We want the device to be able to write a specified amount of bytes to a
specified location in memory, so we'll provide ``addr`` and ``len`` registers.
We will also want a ``running`` register for the CPU to signal that the device
should start operation and a ``complete`` register for the device to signal to
the CPU that it has completed.

.. code-block:: scala

    class InputStreamModuleImp(outer: InputStream) extends LazyModuleImp(outer) {
        val addrBits = 64
        val w = 64
        val io = IO(new Bundle {
            // Not used yet
            val in = Flipped(Decoupled(UInt(w.W)))
        }
        val addr = Reg(UInt(addrBits.W))
        val len = Reg(UInt(addrBits.W))
        val running = RegInit(false.B)
        val complete = RegInit(false.B)

        outer.regnode.regmap(
            0x00 -> Seq(RegField(addrBits, addr)),
            0x08 -> Seq(RegField(addrBits, len)),
            0x10 -> Seq(RegField(1, running)),
            0x18 -> Seq(RegField(1, complete)))
    }

The arguments to ``regmap`` should be a series of mappings from address
offsets to sequences of ``RegField`` objects. The ``RegField`` constructor
takes two arguments, the width of the register field and the RTL register
itself.
