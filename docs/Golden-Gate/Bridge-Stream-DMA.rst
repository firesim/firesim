Bridge Stream DMA on U250 and F2
================================

Bridge drivers use ``push()`` and ``pull()`` to exchange 512-bit words with
FPGA-hosted bridge modules. ``StreamFromHostCPU`` and ``StreamToHostCPU`` name
the direction of a stream; the platform's stream engine chooses how its
payload moves. A single engine serves all host-facing bridge streams.

How the U250 works
------------------

On the XDMA-based U250, the host driver writes to ``/dev/xdmaN_h2c_0`` or
reads from ``/dev/xdmaN_c2h_0``. The file offset is a stream-specific FPGA
address. XDMA performs the transfer, and ``CPUManagedStreamEngine`` decodes
that address to fill or drain the selected FPGA FIFO. The host initiates the
bulk transfer in either direction.

How the F2 PCIM mode works
--------------------------

The F2 shell has no XDMA engine. ``BaseF2PCIMConfig`` selects
``FPGAManagedStreamEngine`` instead: FireSim's FPGA logic initiates the bulk
transfers through PCIM in both directions.

.. code-block:: text

    U250: host RAM <--> XDMA <--> CPUManagedStreamEngine <--> stream FIFOs
          driver submits XDMA read/write at a stream-specific AXI address

    F2:   host RAM <--> shared PCIM <--> FPGAManagedStreamEngine <--> stream FIFOs
          driver -- H2C descriptor over PCIS --> per-stream read mover

    <--> denotes separate H2C and C2H payload transfers.

For H2C, the host copies data into a hugepage-backed buffer and writes four ordered
32-bit words through BAR4/PCIS: address low, address high, byte length, and
stream ID. The stream ID write submits the descriptor. Each stream has its
own descriptor queue and PCIM read mover. The mover fetches the payload into
that stream's FIFO; its completion counter advances once the read finishes.

.. code-block:: text

    push(src)
       |-- copy payload --> hugepage-backed host staging slot
       |-- PCIS writes --> [address low, address high, length, stream ID]
                                           | stream ID is the doorbell
                                           v
                                per-stream descriptor queue
                                           | PCIM reads the staging slot
                                           v
                                    per-stream FPGA FIFO
                                           | final read beat: OCL completion advances
                                           v
                                  host may reuse staging slot

The host checks FIFO occupancy before submitting a descriptor and reserves
space for reads still in flight. It can reuse a staging slot after the
descriptor completes; ``push_flush()`` waits for these reads, not for the
bridge to consume the FIFO. For C2H, each FPGA stream writes to its own host
memory ring. The H2C readers and C2H writers share a PCIM crossbar, which
arbitrates their AXI transactions. OCL carries per-stream counts and status,
not payloads.

This changes the transport selected by the platform; bridge modules keep
the same stream-direction traits and drivers keep the ``push()``/``pull()``
interface. See :gh-file-ref:`sim/midas/src/main/scala/midas/core/CPUManagedStreamEngine.scala`,
:gh-file-ref:`sim/midas/src/main/scala/midas/core/FPGAManagedStreamEngine.scala`,
and :gh-file-ref:`sim/midas/src/main/cc/bridges/fpga_managed_stream.cc`.
