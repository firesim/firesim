package Designs

import Chisel._
import scala.collection.mutable.ArrayBuffer

class FIR2DIO(elementSize: Int, kernelSize: Int) extends Bundle {
    val in  = Decoupled(Bits(width = elementSize)).flip
    val out = Decoupled(Bits(width = elementSize))
    val debug = Vec.fill(kernelSize*kernelSize){ Bits(OUTPUT, width = elementSize) }
}

class FIR2D(elementSize: Int, lineSize: Int, kernelSize: Int) extends Module {
    val io = new FIR2DIO(elementSize, kernelSize)

    // control
    val counter  = Reg(init = UInt(0, width = 10))
    val isFull   = counter > UInt((kernelSize-1)*lineSize)
    val outValid = isFull && io.in.valid
    val inReady  = !isFull || io.out.ready
    val shiftEn  = io.in.valid && inReady
    when (shiftEn) { counter := counter + UInt(1) }
    io.in.ready  := inReady
    io.out.valid := outValid

    // delay lines
    val pix = new ArrayBuffer[Bits]()
    var data = ShiftRegister(io.in.bits, 1, shiftEn)
    pix += data
    for (i <- 0 until (kernelSize-1)) {
        for (j <- 0 until (kernelSize-1)) {
            data = ShiftRegister(data, 1, shiftEn)
            pix += data
        }
        data = ShiftRegister(data, (lineSize - kernelSize + 1), shiftEn)
	pix += data
    }
    for (i <- 0 until (kernelSize-1)) {
        data = ShiftRegister(data, 1, shiftEn)
        pix += data
    }

    // debug outs
    for (i <- 0 until kernelSize*kernelSize) { io.debug(i) := pix(i) }
}
