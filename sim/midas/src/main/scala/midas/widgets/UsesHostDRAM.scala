// See LICENSE for license details.

package midas.widgets

import freechips.rocketchip.amba.axi4.AXI4OutwardNode
import freechips.rocketchip.diplomacy.{AddressSet, TransferSizes}

/**
  * Constrains the "virtual" memory region as seen by Bridge.
  *
  * @param address AddressSets defining the addresses the bridge will access.
  * These addresses may overlap with other bridges, Golden Gate will ensure
  * isolation by using a base-and-bound scheme based on the range of the
  * requested addresses. THe reqested addresses need not be contiguous and
  * their union need not being at address 0.  Currently non-contiguous regions
  * will be allocated memory as though it were continguous.
  *
  * @param supportsRead TransferSize specifying the range of read transaction
  * sizes this bridge will produce
  *
  * @param supportsWrite TransferSize specifying the range of write transaction
  * sizes this bridge will produce
  *
  * Example. Request 4GiB of host DRAM, with read and write transactions
  * that range between 1 and 64B:
  *   MemorySlaveConstraints(AddressSet(0, 0x3FFF_FFFF), TransferSizes(1, 64), TransferSizes(1, 64))
  *
  * Q: Perhaps the last two parameters should be removed, and the bridge should
  * be forced to work the slaves it is given?
  *
  */
case class MemorySlaveConstraints(address: Seq[AddressSet], supportsRead: TransferSizes, supportsWrite: TransferSizes)

/**
  * A common trait for referring collateral in the generated header.
  *
  */
trait HostDramHeaderConsts {
  /*
   * A string identifier the requested memory region.
   * NOTE: Bridges that provide the same region name will share the same
   * allocated DRAM region. See [[UsesHostDRAM]].
   *
   * Setting the regionName to the bridge's instace name (.getWName) will suffice
   * to give it an independent region.
   *
   */
  def memoryRegionName: String
}

/**
  * A BridgeModule mixin indicating it wishes to be allocated FPGA DRAM.
  */
trait UsesHostDRAM extends HostDramHeaderConsts {
  self: Widget =>
  /**
    * The AXI4 master node the Bridge will use to present memory requests to
    * its allocated DRAM.
    */
  def memoryMasterNode: AXI4OutwardNode
  /**
    * A specification for the memory region this Bridge wishes to be allocated.
    * See [[MemorySlaveConstraints]] for more explanation.
    */
  def memorySlaveConstraints: MemorySlaveConstraints
}

private[midas] object BytesOfDRAMRequired {
  // Returns the difference between the bounds of the range that the requested
  // address set spans, neglecting discontinuities
  def apply(requestedAddrs: Seq[AddressSet]): BigInt = {
    val base = requestedAddrs.map(_.base).min
    val max = requestedAddrs.map(_.max).max
    max - base + 1
  }
}
