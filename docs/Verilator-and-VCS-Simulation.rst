Verilator and VCS Simulation
============================

TODO

currently, you must remove the NIC since DMA\_PCIS is not supported in
vcs
https://github.com/firesim/firesim/wiki/Remove-NIC-from-Generator.scala

::

    make DESIGN=FireSimNoNIC verilator

    make run-asm-tests

