// See LICENSE for license details
#ifndef __SYSTEMATIC_SCHEDULER_H
#define __SYSTEMATIC_SCHEDULER_H

#include <cstdint>
#include <functional>
#include <vector>

// Maximum step size in MIDAS's master is capped to width of the simulation bus
constexpr uint64_t MAX_MIDAS_STEP = (1LL << sizeof(uint32_t) * 8) - 1;

// Schedules a series of tasks that must run a specific cycles on the FPGA, but
// may be associated with multiple bridges / touch non-bridge simulator
// collateral (e.g, reservoir sampling for strober.)
//
// This class does that by providing cycle deltas (i.e., a credit) that can be
// fed into some mechanism (e.g., peek/poke or a bridge) to advance the
// simulator to the time of the desired task. The onus is on the parent class /
// instantiator to ensure the simulator has advanced to the desired cycle
// before running the scheduled task.
class systematic_scheduler_t {
  using task_t = std::function<uint64_t()>;
  using task_tuple_t = struct {
    task_t task;
    uint64_t next_cycle;
  };

public:
  // Adds a new task to scheduler.
  void register_task(task_t &&task, uint64_t first_cycle);
  // Calculates the next simulation step by taking the min of all
  // tasks.next_cycle
  uint32_t get_largest_stepsize();
  // Assumption: The simulator is idle. (simif::done() == true)
  // Invokes all tasks that wish to be executed on our current target cycle
  void run_scheduled_tasks();
  // Unless overriden, assume the simulator will run (effectively) forever
  uint64_t max_cycles = -1;
  // Returns true if no further tasks are scheduled before specified horizon
  // (max_cycles).
  bool finished_scheduled_tasks() { return current_cycle == max_cycles; };

private:
  uint64_t default_step_size = MAX_MIDAS_STEP;
  uint64_t current_cycle = 0;
  std::vector<task_tuple_t> tasks;
};
#endif // __SYSTEMATIC_SCHEDULER_H
