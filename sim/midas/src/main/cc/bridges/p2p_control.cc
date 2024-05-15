#include "p2p_control.h"
#include "core/simif.h"
#include <stdio.h>
#include <inttypes.h>

char p2p_control_t::KIND;


p2p_control_t::p2p_control_t(
    simif_t &simif,
    const P2PCONTROLBRIDGE_struct &mmio_addrs,
    unsigned index,
    const std::vector<std::string> & args)
  : widget_t(simif, &KIND), mmio_addrs(mmio_addrs) {

  std::string peer_pcis_offset = std::string("+peer-pcis-offset=");
  for (auto &arg: args) {
    if (arg.find(peer_pcis_offset) == 0) {
      char *str = const_cast<char *>(arg.c_str() + peer_pcis_offset.length());
      this->addr_offset = strtoul(str, NULL, 16);
    }
  }
}

bool p2p_control_t::aw_deq_valid() {
  return (bool)simif.read(mmio_addrs.aw_addr_deq_valid);
}

uint64_t p2p_control_t::aw_deq_bits() {
  uint64_t lo = simif.read(mmio_addrs.aw_addr_deq_bits_lo);
  uint64_t hi = simif.read(mmio_addrs.aw_addr_deq_bits_hi);
  return (hi << 32LL | lo);
}

uint64_t p2p_control_t::aw_addr_read() {
  uint64_t addr = aw_deq_bits();
  simif.write(mmio_addrs.aw_addr_deq_ready, 1);
  return addr;
}

bool p2p_control_t::ar_deq_valid() {
  return (bool)simif.read(mmio_addrs.ar_addr_deq_valid);
}

uint64_t p2p_control_t::ar_deq_bits() {
  uint64_t lo = simif.read(mmio_addrs.ar_addr_deq_bits_lo);
  uint64_t hi = simif.read(mmio_addrs.ar_addr_deq_bits_hi);
  return (hi << 32LL | lo);
}

uint64_t p2p_control_t::ar_addr_read() {
  uint64_t addr = ar_deq_bits();
  simif.write(mmio_addrs.ar_addr_deq_ready, 1);
  return addr;
}

bool p2p_control_t::m_aw_deq_valid() {
  return (bool)simif.read(mmio_addrs.m_aw_addr_deq_valid);
}

uint64_t p2p_control_t::m_aw_deq_bits() {
  uint64_t lo = simif.read(mmio_addrs.m_aw_addr_deq_bits_lo);
  uint64_t hi = simif.read(mmio_addrs.m_aw_addr_deq_bits_hi);
  return (hi << 32LL | lo);
}

uint64_t p2p_control_t::m_aw_addr_read() {
  uint64_t addr = m_aw_deq_bits();
  simif.write(mmio_addrs.m_aw_addr_deq_ready, 1);
  return addr;
}

bool p2p_control_t::m_ar_deq_valid() {
  return (bool)simif.read(mmio_addrs.m_ar_addr_deq_valid);
}

uint64_t p2p_control_t::m_ar_deq_bits() {
  uint64_t lo = simif.read(mmio_addrs.m_ar_addr_deq_bits_lo);
  uint64_t hi = simif.read(mmio_addrs.m_ar_addr_deq_bits_hi);
  return (hi << 32LL | lo);
}

uint64_t p2p_control_t::m_ar_addr_read() {
  uint64_t addr = m_ar_deq_bits();
  simif.write(mmio_addrs.m_ar_addr_deq_ready, 1);
  return addr;
}

void p2p_control_t::print_incoming_pcis_addrs() {
/* #define P2P_CONTROL_BRIDGE_DEBUG */

#ifdef P2P_CONTROL_BRIDGE_DEBUG
  while (aw_deq_valid()) {
    fprintf(stdout, "s_aw_addr: 0x%" PRIx64 "\n", aw_addr_read());
  }
  while (ar_deq_valid()) {
    fprintf(stdout, "s_ar_addr: 0x%" PRIx64 "\n", ar_addr_read());
  }
  while (m_aw_deq_valid()) {
    fprintf(stdout, "m_aw_addr: 0x%" PRIx64 "\n", m_aw_addr_read());
  }
  while (m_ar_deq_valid()) {
    fprintf(stdout, "m_ar_addr: 0x%" PRIx64 "\n", m_ar_addr_read());
  }
#endif
}
