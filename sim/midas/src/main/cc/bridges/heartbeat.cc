#include "heartbeat.h"

#include <inttypes.h>

heartbeat_t::heartbeat_t(simif_t *sim, std::vector<std::string> &args)
    : bridge_driver_t(sim), sim(sim) {
  auto interval_arg = std::string("+heartbeat-polling-interval=");
  for (auto arg : args) {
    if (arg.find(interval_arg) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + interval_arg.length();
      polling_interval = atol(str);
    }
  }

  log.open("heartbeat.csv", std::ios_base::out);
  if (!log.is_open()) {
    fprintf(stderr, "Could not open heartbeat output file.\n");
    abort();
  }
  log << "Target Cycle (fastest), Seconds Since Start" << std::endl;
  time(&start_time);
}

void heartbeat_t::tick() {
  if (trip_count == polling_interval) {
    trip_count = 0;
    uint64_t current_cycle = sim->actual_tcycle();
    has_timed_out |= current_cycle == last_cycle;

    time_t current_time;
    time(&current_time);
    (void)localtime(&current_time);
    log << current_cycle << ", " << current_time - start_time << std::endl;
    last_cycle = current_cycle;

    if (has_timed_out) {
      fprintf(stderr,
              "Simulator deadlock detected at target cycle %" PRId64
              ". Terminating.\n",
              current_cycle);
    }
  } else {
    trip_count++;
  }
}
