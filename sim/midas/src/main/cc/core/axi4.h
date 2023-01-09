#ifndef __CORE_AXI4
#define __CORE_AXI4

#include <cmath>
#include <cstdint>
#include <optional>

/**
 * Structure carrying the configuration of an AXI4 channel.
 */
struct AXI4Config {
  const uint64_t id_bits;
  const uint64_t addr_bits;
  const uint64_t data_bits;

  /**
   * Return the number of strobe bits.
   */
  uint64_t strb_bits() const { return data_bits / 8; }

  /**
   * Return the number of bytes in a beat.
   */
  uint64_t beat_bytes() const { return strb_bits(); }

  /**
   *  Return the AXI4-lite control interface size.
   */
  uint64_t get_size() const { return ceil(log2(strb_bits())); }

  /**
   * Returns the number of words carried by the channel.
   */
  uint64_t get_data_size() const { return beat_bytes() / sizeof(uint32_t); }
};

#endif // __CORE_AXI4
