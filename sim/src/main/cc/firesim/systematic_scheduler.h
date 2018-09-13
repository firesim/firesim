#ifndef __SYSTEMATIC_SCHEDULER_H
#define __SYSTEMATIC_SCHEDULER_H

#include <functional>
#include <vector>

// Maximum step size in MIDAS's master is 2^32 - 1.
constexpr uint64_t MAX_MIDAS_STEP = (1LL << 32) - 1;

class systematic_scheduler_t
{
    typedef std::function<uint64_t()> task_t;
    typedef struct {
        task_t task;
        uint64_t next_cycle;
    } task_tuple_t;

    public:
        //systematic_scheduler_t(uint64_t default_step_size):
        //    default_step_size(default_step_size) {};

        void register_task(task_t task, uint64_t first_cycle);
        uint32_t get_largest_stepsize();
        void run_scheduled_tasks();
        uint64_t max_cycles = -1;
        bool has_timed_out() { return current_cycle == max_cycles; };

    private:
        uint64_t default_step_size = MAX_MIDAS_STEP;
        uint64_t current_cycle = 0;
        std::vector<task_tuple_t> tasks;
};

#endif // __SYSTEMATIC_SCHEDULER_H
