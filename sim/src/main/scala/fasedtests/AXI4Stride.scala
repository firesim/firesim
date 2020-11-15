//See LICENSE for license details.

package firesim.fasedtests

import chisel3._
import chisel3.util._

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util._
import junctions.{NastiKey, NastiParameters}
import midas.models.{FASEDBridge, AXI4EdgeSummary, CompleteConfig}
import midas.widgets.{PeekPokeBridge, RationalClockBridge}


class Strider(stride: Int, addr_width: Int, init_addr: Int) extends Module{
    // Output value 
    val io = IO(new Bundle {
	val ready = Input(Bool())
        //val output_queue = util.Queue(UInt(addr_width.W), 32) //32 entry address queue
        val out = Decoupled(UInt(addr_width.W)) //Default is producer
    })
    io.out.valid := true.B
    //initialize register with initial value parametrized with init_addr
    val address = RegInit(init_addr.U(addr_width.W))
    io.out.bits := address
    when(io.ready) {
        // output is the current register value
        //io.output_queue.enq(x)
        // at the next clock cycle, update register with the value + the stride
        address := address + stride.U
    }
}

class StriderInterconnect(params: AXI4BundleParameters, init_addr: Int, stride: Int, num_t: Int, num_beats: Int) extends MultiIOModule {
   /*Test Pseudocode
   Mem[addr] = value
   offset = 0 -> handled by strider
   counter = 0 -> handled by test
   prev_addr = addr -> Register countaining previous address
   addr += stride
   while(counter < numTransactions):
     assert(Mem[prev_addr] = value)
     Mem[addr] = value
     addr += stride
   */
   
   val peekpoke = IO(new Bundle{
                  val done = Output(Bool())
                  val error = Output(Bool())
                  })
   val data = 10.U(params.dataBits.W) //datavalue to write
   
   val strider = Module(new Strider(stride, params.addrBits, init_addr + stride))
   val axi4 = IO(new AXI4Bundle(params))
   val write_addr = RegInit(init_addr.U(params.addrBits.W))
   val read_addr = RegInit(init_addr.U(params.addrBits.W))
   val write_data = RegInit(0.U(log2Ceil(num_t).W))
   val write_counter = RegInit(0.U(log2Ceil(num_beats*num_t).W))
   val write_t_counter = RegInit(0.U(log2Ceil(num_t).W))
   val data_counter = RegInit(1.U(log2Ceil(num_t).W))
   val read_counter = RegInit(0.U(log2Ceil(num_t).W))
   val first_cycle = RegInit(true.B)
   val write_last = RegInit(false.B)

   //Setting fields for aw channel
   axi4.aw.bits.id     := 1.U //helps determine orderings in which the transactions are meant to be completed
   axi4.aw.bits.len    := (num_beats - 1).U//length of burst - 1 : 1-256 for INCR burst type, 1-16 for others
   axi4.aw.bits.size   := (log2Ceil(params.dataBits/8)).U//(params.dataBits / 8).U //number of bytes per beat is 2^size!!
   axi4.aw.bits.burst  := 1.U //set burst type to incrementing
   axi4.aw.bits.lock   := 0.U //set lock type to normal
   axi4.aw.bits.cache  := 0.U //write back write allocate cache?? don't worry about it (set 0 uncached)
   axi4.aw.bits.prot   := 0.U //priviledged, non-secure data access
   axi4.aw.bits.qos    := 0.U //not participating in QOS scheme

   //Setting fields for w channel
   axi4.w.bits.strb    := "hff".U    //Every byte of the write will be propogated
   //axi4.w.bits.last    := false.B    //asserted only if driving the final write transfer in the burst
   //Should the above be true?
   //Implement multi-beat transactions
   //axi4.w.bits.last    := true.B //Burst length is 1, so we always driving the last transfer in the burst
   if (num_beats == 1) {
      axi4.w.bits.last := true.B 
   } else {
      axi4.w.bits.last := write_last
   }
   //Setting fields for ar channel
   axi4.ar.bits.id     := 1.U //helps determine orderings in which the transactions are meant to be completed
   axi4.ar.bits.len    := (num_beats - 1).U///length of burst - 1 : 1-256 for INCR burst type, 1-16 for others
   axi4.ar.bits.size   := (log2Ceil(params.dataBits/8)).U//(params.dataBits / 8).U //number of bytes per beat
   axi4.ar.bits.burst  := 1.U //set burst type to incrementing
   axi4.ar.bits.lock   := 0.U //set lock type to normal
   axi4.ar.bits.cache  := 0.U //write back write allocate cache?? don't worry about it (set 0 uncached)
   axi4.ar.bits.prot   := 0.U //priviledged, non-secure data access
   axi4.ar.bits.qos    := 0.U //not participating in QOS scheme

   //Enqueue addresses (queue object in chisel)
   //Register all fields associated with specific transactions
   //Other circuit may have dependencies in the reverse direction
   //Provide new values when both ready and valid are asserted
   //Send out aw and w transactions independently -> don't fire them in the same cycle -> not strictly sound to wait for both of them to be ready
   //When strider address is ready, write data to address w/ aw and w channels 
   //draw schematic for this to convince

   //Deal with Queue
   //When queue is not full, continuously generate addresses and enqueue
   //Otherwise, stop generating address and stop enqueueing
   //Connect Dequeue line to curr_addr register
   //val strider_rdy = RegInit(true.B)
   //strider.io.ready := strider_rdy

   //val addr_q = Module(new Queue(strider.io.out.bits, 32)) //32 entry queue
   val q_width = 32
   val write_addr_q = Module(new Queue(UInt(params.addrBits.W), q_width)) //32 entry queue
   write_addr_q.io.enq <> strider.io.out
   //try not using a register for ready, fix from there
   val write_addr_deq_ready = RegInit(false.B)
   write_addr_q.io.deq.ready := write_addr_deq_ready
   strider.io.ready := write_addr_q.io.enq.ready
    
   val hold_counter = RegInit(0.U((if (num_beats == 1) 1 else log2Ceil(num_beats)).W))
   val read_addr_hold_q = Module(new Queue(UInt(params.addrBits.W), q_width)) //32 entry queue
   val read_addr_hold_enq_valid = RegInit(false.B)
   read_addr_hold_q.io.enq.bits := write_addr
   read_addr_hold_q.io.enq.valid := read_addr_hold_enq_valid
   //try not using a register for ready, fix from there
   val read_addr_hold_deq_ready = RegInit(false.B)
   read_addr_hold_q.io.deq.ready := read_addr_hold_deq_ready

   val read_addr_q = Module(new Queue(UInt(params.addrBits.W), q_width)) //32 entry queue
   val read_addr_enq_valid = RegInit(false.B)
   read_addr_q.io.enq.bits := read_addr_hold_q.io.deq.bits
   read_addr_q.io.enq.valid := read_addr_enq_valid
   //try not using a register for ready, fix from there
   val read_addr_deq_ready = RegInit(true.B)
   read_addr_q.io.deq.ready := read_addr_deq_ready

   val write_data_hold_q = Module(new Queue(UInt(log2Ceil(num_t).W), q_width))
   val write_data_hold_enq_valid = RegInit(false.B)
   write_data_hold_q.io.enq.bits := data_counter//write_counter
   write_data_hold_q.io.enq.valid := write_data_hold_enq_valid
   val write_data_hold_deq_ready = RegInit(false.B)
   write_data_hold_q.io.deq.ready := write_data_hold_deq_ready

   val write_data_q = Module(new Queue(UInt(log2Ceil(num_t).W), q_width))
   val write_data_enq_valid = RegInit(false.B)
   write_data_q.io.enq.bits := write_data_hold_q.io.deq.bits
   write_data_q.io.enq.valid := write_data_enq_valid
   val write_data_deq_ready = RegInit(true.B)
   write_data_q.io.deq.ready := write_data_deq_ready
  
   //Initialise all registers for fields passed to peekpoke bridge and axi4 interface
   val peek_done = RegInit(false.B)
   val peek_error = RegInit(false.B)
   val aw_valid = RegInit(true.B)
   val w_valid = RegInit(true.B)
   val b_ready = RegInit(false.B)
   val r_ready = RegInit(false.B)
   val ar_valid = RegInit(false.B)
   
   axi4.aw.bits.addr := write_addr
   axi4.w.bits.data := data_counter//write_counter
   axi4.ar.bits.addr := read_addr
   /*when(axi4.r.bits.last) {
      peek_done := true.B
   }*/
   //Connect registers to the different fields
   peekpoke.done := peek_done
   peekpoke.error := peek_error
   axi4.aw.valid := aw_valid
   axi4.w.valid := w_valid
   axi4.b.ready := b_ready
   axi4.ar.valid := ar_valid
   axi4.r.ready := r_ready
   val aw_assert = RegInit(false.B)
   val ar_assert = RegInit(false.B)
   val wp_last_cycle = RegInit(false.B)
   val rp_last_cycle = RegInit(false.B)
  when(write_t_counter <= (num_t/2).U) {//write_counter <= (num_t/2 - num_beats).U) {//|| wp_last_cycle) {
    when(axi4.aw.fire() || aw_assert) {
      aw_valid := false.B
      aw_assert := false.B
      write_addr_deq_ready := true.B
      read_addr_hold_enq_valid := true.B
    }
    when(axi4.w.fire()) {
      w_valid := false.B
      write_last := false.B
      write_data_hold_enq_valid := true.B
    }
    when(write_addr_q.io.deq.fire() && write_data_hold_q.io.enq.fire() && read_addr_hold_q.io.enq.fire()) {
      write_addr_deq_ready := false.B
      write_data_hold_enq_valid := false.B
      read_addr_hold_enq_valid := false.B
      write_addr := write_addr_q.io.deq.bits
      when(write_counter === (num_t/2 - num_beats).U) {
        wp_last_cycle := true.B
      }
      data_counter := data_counter + 1.U
      write_counter := write_counter +1.U
      when((write_counter % num_beats.U) === (num_beats-1).U) {
        b_ready := true.B
        aw_assert := false.B
        //write_data_enq_valid := true.B
        //read_addr_enq_valid := true.B
        //read_addr_hold_deq_ready := true.B
        //write_data_hold_deq_ready := true.B
      }.otherwise {
        if (num_beats != 1) {
          when((write_counter % num_beats.U) === (num_beats-2).U){
            write_last := true.B
            //peek_done := true.B
          }
        }
        aw_assert := true.B
        //aw_valid := true.B
        w_valid := true.B
      }
    }
    when(axi4.b.fire()) {
      hold_counter := 0.U
      //write_counter := write_counter + num_beats.U
      b_ready := false.B
      write_data_hold_deq_ready := true.B
      read_addr_hold_deq_ready := true.B
      write_data_enq_valid := true.B
      read_addr_enq_valid := true.B
    }
  }
  
    when(write_data_q.io.enq.fire() && read_addr_q.io.enq.fire() && write_data_hold_q.io.deq.fire() && read_addr_hold_q.io.deq.fire()) {
      hold_counter := hold_counter + 1.U
      when((hold_counter % num_beats.U) === (num_beats-1).U) {
        write_t_counter := write_t_counter + 1.U
        write_data_enq_valid := false.B
        read_addr_enq_valid := false.B
        read_addr_hold_deq_ready := false.B
        write_data_hold_deq_ready := false.B
        aw_valid := true.B
        aw_assert := false.B
        w_valid := true.B
        wp_last_cycle := false.B
      }
    }
    when(read_counter <= (num_t/2).U) { 
      when(read_addr_q.io.deq.fire() && write_data_q.io.deq.fire()) {
        read_addr_deq_ready := false.B
        write_data_deq_ready := false.B
        write_data := write_data_q.io.deq.bits
        read_addr := read_addr_q.io.deq.bits
        when((read_counter % num_beats.U) === 0.U) {//(num_beats-1).U) {
           ar_valid := true.B
        }.otherwise {
           ar_assert := true.B
        }
      }
      when(axi4.ar.fire() || ar_assert) {
	 ar_valid := false.B
         r_ready := true.B
      }
      when(axi4.r.fire()) {
	 r_ready := false.B
	 when(axi4.r.bits.data =/= write_data) {
	    peek_error  := true.B
            peek_done := true.B
	 }
         when(axi4.r.bits.last) {
            ar_assert := false.B
         }
         read_addr_deq_ready := true.B
         write_data_deq_ready := true.B
	 read_counter := read_counter + 1.U
      }
   }.otherwise {
      peek_done := true.B
   }
}

class AXI4Strider(implicit val p: Parameters) extends RawModule {
  val clockBridge = RationalClockBridge()//Module(new RationalClockBridge())
  val clock = clockBridge.io.clocks(0)
  val reset = WireInit(false.B)
  val done = true.B
  val error = false.B
  //dataBits = number of bytes in a beat * number of bits in a byte 
  val strideParams = new AXI4BundleParameters(p(AddrBits), p(BeatBytes)*8, p(IDBits))
  //val axi4 = new AXI4Bundle(strideParams)
  //val axi4ar = new AXI4BundleAR(strideParams)
  val init_addr = 32
  val stride = p(AXI4StrideLength)
  withClockAndReset(clock, reset){
    val strider = Module(new StriderInterconnect(strideParams, init_addr, stride, p(NumTransactions), p(NumBeats)))

    val nastiKey = NastiParameters(p(BeatBytes) * 8, p(AddrBits), p(IDBits))
  
    val fasedInstance = FASEDBridge(clock, strider.axi4, reset, CompleteConfig(p(firesim.configs.MemModelKey), nastiKey))
    
    val peekPokeBridge = PeekPokeBridge(clock, reset, ("done", strider.peekpoke.done), ("error", strider.peekpoke.error))
  }
}
