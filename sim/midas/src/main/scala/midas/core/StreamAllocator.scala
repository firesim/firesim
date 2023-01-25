// See LICENSE for license details.

package midas.core

import freechips.rocketchip.config.Field

/**
  * Does the dirty work of providing an index to each stream, and checking its
  * requested name is unique.
  *
  * Instead of making these truly global data structures, they are snuck in to
  * the bridges via p, using the [[ToCPUStreamAllocatorKey]] and
  * [[FromCPUStreamAllocatorKey]].
  *
  * It's worth noting this is the sort of thing diplomacy would handle for us,
  * if I put in the time to defined node types for these streams. That is probably
  * worth doing in a future PR, but is a significant amount of boiler plate.
  *
  */
private [midas] class StreamAllocator {
  private var idx = 0;
  private val namespace = firrtl.Namespace()

  /**
    * Uniquifies the name for a stream, and returns its index
    */
  def allocate(desiredName: String): (String, Int) = {
    val allocatedId = idx;
    idx = idx + 1
    // This is imposed only so that the generated header's output names are
    // predictable (so they can be referred to statically in driver source).
    // Once a bridge's driver parameters are better encapsulated (i.e., in their
    // own structs / protobuf), this can be relaxed.
    // In practice, this shouldn't trip since the names are prefixed with the
    // bridge names which are already uniquified.
    require(!namespace.contains(desiredName),
      s"Stream name ${desiredName}, already allocated. Requested stream names must be unique.")
    (namespace.newName(desiredName), allocatedId)
  }
}

private [midas] case object ToCPUStreamAllocatorKey extends Field[StreamAllocator](new StreamAllocator)
private [midas] case object FromCPUStreamAllocatorKey extends Field[StreamAllocator](new StreamAllocator)
