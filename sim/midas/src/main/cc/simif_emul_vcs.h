// See LICENSE for license details.

#ifndef __SIMIF_EMUL_VCS_H
#define __SIMIF_EMUL_VCS_H

#include <atomic>
#include <memory>
#include <optional>

#include "simif_emul.h"

/// Helper to handle signals.
void emul_signal_handler(int sig);

/**
 * VCS-specific metasimulator implementation.
 */
class simif_emul_vcs_t final : public simif_emul_t {
public:
  simif_emul_vcs_t(const std::vector<std::string> &args);
  ~simif_emul_vcs_t();
};

#endif // __SIMIF_EMUL_VCS_H
