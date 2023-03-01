// See LICENSE for license details

#include "systematic_scheduler.h"

#include <cassert>

systematic_scheduler_t::systematic_scheduler_t(
    const std::vector<std::string> &args) {
  for (const auto &arg : args) {
    if (arg.find("+max-cycles=") == 0) {
      max_cycles = atoi(arg.c_str() + 12);
    }
  }
}

void systematic_scheduler_t::register_task(uint64_t first_cycle,
                                           task_t &&task) {
  tasks.push_back(task_tuple_t{std::move(task), first_cycle});
}

uint32_t systematic_scheduler_t::get_largest_stepsize() {
  uint64_t next_cycle = current_cycle + default_step_size;
  if (max_cycles && *max_cycles < next_cycle) {
    next_cycle = *max_cycles;
  }

  for (auto &t : tasks) {
    if (t.next_cycle < next_cycle) {
      next_cycle = t.next_cycle;
    }
  }

  assert(next_cycle - current_cycle <= MAX_MIDAS_STEP);
  uint32_t step = next_cycle - current_cycle;
  assert(step != 0); // Check for forward progress.
  current_cycle = next_cycle;
  return step;
}

void systematic_scheduler_t::run_scheduled_tasks() {
  for (auto &t : tasks) {
    if (t.next_cycle == current_cycle) {
      t.next_cycle += t.task();
    }
  }
}
