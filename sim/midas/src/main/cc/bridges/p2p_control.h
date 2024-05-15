#ifndef _P2P_CONTROL_H_
#define _P2P_CONTROL_H_


#include "core/widget.h"
#include <cstdint>
#include <string>
#include <vector>

class simif_t;

struct P2PCONTROLBRIDGE_struct {
  uint64_t aw_addr_deq_valid;
  uint64_t aw_addr_deq_bits_lo;
  uint64_t aw_addr_deq_bits_hi;
  uint64_t aw_addr_deq_ready;
  uint64_t ar_addr_deq_valid;
  uint64_t ar_addr_deq_bits_lo;
  uint64_t ar_addr_deq_bits_hi;
  uint64_t ar_addr_deq_ready;
  uint64_t m_aw_addr_deq_valid;
  uint64_t m_aw_addr_deq_bits_lo;
  uint64_t m_aw_addr_deq_bits_hi;
  uint64_t m_aw_addr_deq_ready;
  uint64_t m_ar_addr_deq_valid;
  uint64_t m_ar_addr_deq_bits_lo;
  uint64_t m_ar_addr_deq_bits_hi;
  uint64_t m_ar_addr_deq_ready;
};

class p2p_control_t final : public widget_t {
public:
  /// The identifier for the bridge type.
  static char KIND;

  p2p_control_t(
      simif_t &simif,
      const P2PCONTROLBRIDGE_struct &mmio_addrs,
      unsigned index,
      const std::vector<std::string> &args);

  void print_incoming_pcis_addrs();

private:
  bool aw_deq_valid();
  uint64_t aw_deq_bits();
  uint64_t aw_addr_read();

  bool ar_deq_valid();
  uint64_t ar_deq_bits();
  uint64_t ar_addr_read();

  bool m_aw_deq_valid();
  uint64_t m_aw_deq_bits();
  uint64_t m_aw_addr_read();

  bool m_ar_deq_valid();
  uint64_t m_ar_deq_bits();
  uint64_t m_ar_addr_read();

private:
  const P2PCONTROLBRIDGE_struct mmio_addrs;
  uint64_t addr_offset = 0LL;
};

#endif //_P2P_CONTROL_H_
