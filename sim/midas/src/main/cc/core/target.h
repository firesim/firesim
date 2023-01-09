#ifndef __CORE_TARGET_H
#define __CORE_TARGET_H

#include "core/axi4.h"

/**
 * Structure carrying the configuration of a target.
 */
struct TargetConfig {
  const AXI4Config ctrl;

  const AXI4Config mem;
  const unsigned mem_num_channels;

  const std::optional<AXI4Config> cpu_managed;

  const std::optional<AXI4Config> fpga_managed;

  const char *target_name;
};

#endif // __CORE_TARGET_H
