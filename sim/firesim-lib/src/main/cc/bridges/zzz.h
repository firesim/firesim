// See LICENSE for license details

#ifndef __ZZZ_H
#define __ZZZ_H

#include "core/bridge_driver.h"
#include "bridges/serial_data_zzz.h"


#include <cstdint>
#include <memory>
#include <optional>
#include <signal.h>
#include <string>
#include <vector>

/**
 * Structure carrying the addresses of all fixed MMIO ports.
 *
 * This structure is instantiated when all bridges are populated based on
 * the target configuration.
 */
struct ZZZBRIDGEMODULE_struct {
  uint64_t outDN_bits;
  uint64_t outDN_valid;
  uint64_t outDN_ready;
  // uint64_t out_bits;
  // uint64_t out_valid;
  // uint64_t out_ready;
  uint64_t inDN_bits;
  uint64_t inDN_valid;
  uint64_t inDN_ready;
  // uint64_t in_bits;
  // uint64_t in_valid;
  // uint64_t in_ready;
};

/**
 * Base class for callbacks handling data coming in and out a UART stream.
 */
class zzz_handler {
public:
  virtual ~zzz_handler() = default;

  virtual std::optional<char> get() = 0;
  virtual void put(char data) = 0;
};

class zzz_t final : public bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  /// Creates a bridge which interacts with standard streams or PTY.
  zzz_t(simif_t &simif,
         const ZZZBRIDGEMODULE_struct &mmio_addrs,
         int zzzno,
         const std::vector<std::string> &args);

  ~zzz_t() override;

  void tick() override;

private:
  const ZZZBRIDGEMODULE_struct mmio_addrs;
  std::unique_ptr<zzz_handler> handler;

  serial_data_t<char> data;

  void send();
  void recv();
};

#endif // __UART_H
