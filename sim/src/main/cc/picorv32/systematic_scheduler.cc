#include <assert.h>
#include "systematic_scheduler.h"

void systematic_scheduler_t::register_task(task_t task, uint64_t first_cycle) {
   tasks.push_back(task_tuple_t{task, first_cycle});
}

uint32_t systematic_scheduler_t::get_largest_stepsize() {
    uint64_t next_cycle = std::min(current_cycle + default_step_size, max_cycles);
    for (auto &t: tasks) {
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
    for (auto &t: tasks) {
        if (t.next_cycle == current_cycle) {
            t.next_cycle += t.task();
         }
    }
}
