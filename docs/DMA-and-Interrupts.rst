DMA and Interrupts
==================

TileLink Client Port
--------------------

In order to move data from the external input stream to memory, we need to
perform direct memory access (DMA). We can achieve this by giving the device
a TLClientNode. Once we add it, the ``LazyModule`` will now look like this:

.. code-block:: scala

    class InputStream(
        address: BigInt,
        val beatBytes: Int = 8,
        val maxInflight: Int = 4)
        (implicit p: Parameters) extends LazyModule {
    
      val device = new SimpleDevice("input-stream", Seq("example,input-stream"))
      val regnode = TLRegisterNode(
        address = Seq(AddressSet(address, 0x3f)),
        device = device,
        beatBytes = beatBytes)
      val dmanode = TLClientNode(Seq(TLClientPortParameters(
        Seq(TLClientParameters(
          name = "input-stream",
          sourceId = IdRange(0, maxInflight))))))
    
      lazy val module = new InputStreamModuleImp(this)
    }

For our ``TLClientNode``, we only need a single port, so we specify a single
set of ``TLClientPortParameters`` and ``TLClientParameters``. We override two
arguments in the ``TLClientParameters`` constructor. The ``name`` is the
name of the port and ``sourceId`` indicates the range of transaction IDs
that can be used in memory requests. The lower bound is inclusive, and the
upper bound is exclusive, so this device can use source IDs from 0 to
``maxInflight - 1``.

TileLink Protocol and State Machine
-----------------------------------

In the module implementation, we can now implement a state machine that
sends write requests to memory. We first call `outer.dmanode.out` to get
a sequence of output port tuples. Since we only have one port, we can just
pull out the first element of this sequence. For each port, we get a pair of
objects. The first is the physical TileLink port, which we can connect to RTL.
The second is a ``TLEdge`` object, which we can use to get extra metadata about
the tilelink port (like the number of address and data bits). 

.. code-block:: scala

    class InputStreamModuleImp(outer: InputStream) extends LazyModuleImp(outer) {
      val (tl, edge) = outer.dmanode.out(0)
      val addrBits = edge.bundle.addressBits
      val w = edge.bundle.dataBits
      val beatBytes = (w / 8)

      val io = IO(new Bundle {
        val in = Flipped(Decoupled(UInt(w.W)))
      })

      val addr = Reg(UInt(addrBits.W))
      val len = Reg(UInt(addrBits.W))
      val running = RegInit(false.B)
      val complete = RegInit(false.B)

      val s_idle :: s_issue :: s_wait :: Nil = Enum(3)
      val state = RegInit(s_idle)

      val nXacts = outer.maxInflight
      val xactBusy = RegInit(0.U(nXacts.W))
      val xactOnehot = PriorityEncoderOH(~xactBusy)
      val canIssue = (state === s_issue) && !xactBusy.andR

      io.in.ready := canIssue && tl.a.ready
      tl.a.valid  := canIssue && io.in.valid
      tl.a.bits   := edge.Put(
        fromSource = OHToUInt(xactOnehot),
        toAddress = addr,
        lgSize = log2Ceil(beatBytes).U,
        data = io.in.bits)._2
      tl.d.ready := running && xactBusy.orR

      xactBusy := (xactBusy |
        Mux(tl.a.fire(), xactOnehot, 0.U(nXacts.W))) &
        ~Mux(tl.d.fire(), UIntToOH(tl.d.bits.source), 0.U)

      when (state === s_idle && running) {
        assert(addr(log2Ceil(beatBytes)-1,0) === 0.U,
          s"InputStream base address not aligned to ${beatBytes} bytes")
        assert(len(log2Ceil(beatBytes)-1,0) === 0.U,
          s"InputStream length not aligned to ${beatBytes} bytes")
        state := s_issue
      }

      when (io.in.fire()) {
        addr := addr + beatBytes.U
        len := len - beatBytes.U
        when (len === beatBytes.U) { state := s_wait }
      }

      when (state === s_wait && !xactBusy.orR) {
        running := false.B
        complete := true.B
        state := s_idle
      }

      outer.regnode.regmap(
        0x00 -> Seq(RegField(addrBits, addr)),
        0x08 -> Seq(RegField(addrBits, len)),
        0x10 -> Seq(RegField(1, running)),
        0x18 -> Seq(RegField(1, complete)))
    }

The state machine starts in the ``s_idle`` state. In this state, the CPU should
set the ``addr`` and ``len`` registers and then set the ``running`` register to
1. The state machine then moves into the ``s_issue`` state, in which it
forwards data from the ``in`` decoupled interface to memory through the
TileLink `A` channel.

We construct the `A` channel requests using the ``Put`` method in the
``TLEdge`` object we extracted earlier.  The ``Put`` method takes a unique
source ID in ``fromSource``, the address to write to in ``toAddress``, the
base-2 logarithm of the size in bytes in ``lgSize``, and the data to be written
in ``data``.

The source field must observe some constraints. There can only be one
transaction with each distinct source ID in flight at a given time.
Once you send a request on the `A` channel with a specific source ID,
you cannot send another until after you've received the response for it
on the `D` channel.

Once all requests have been sent on the `A` channel, the state machine
transitions to the ``s_wait`` state to wait for the remaining responses on
the `D` channel. Once the responses have all returned, the state machine
sets ``running`` to false and ``completed`` to true. The CPU can poll the
``completed`` register to check if the operation has finished.

Interrupts
----------

For long-running operations, we would like to have the device
notify the CPU through an interrupt. To add an interrupt to the device,
we need to create an ``IntSourceNode`` in the lazy module.

.. code-block:: scala

    val intnode = IntSourceNode(IntSourcePortSimple(resources = device.int))

Then, in the module implementation, we can connect the ``complete`` register
to the interrupt line. That way, the CPU will get interrupted once the
state machine completes. It can clear the interrupt by writing a 0 to the
``complete`` register.

.. code-block:: scala

    val (interrupt, _) = outer.intnode.out(0)

    interrupt(0) := complete
