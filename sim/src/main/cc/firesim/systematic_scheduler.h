#ifndef __SYSTEMATIC_SCHEDULER_H
#define __SYSTEMATIC_SCHEDULER_H

#include <functional>
#include <vector>

// Maximum step size in MIDAS's master is capped to width of the simulation bus
constexpr uint64_t MAX_MIDAS_STEP = (1LL << sizeof(data_t) * 8) - 1;

class systematic_scheduler_t
{
    typedef std::function<uint64_t()> task_t;
    typedef struct {
        task_t task;
        uint64_t next_cycle;
    } task_tuple_t;

    public:
        // Adds a new task to scheduler.
        void register_task(task_t task, uint64_t first_cycle);
        // Calculates the next simulation step by taking the min of all tasks.next_cycle
        uint32_t get_largest_stepsize();
        // Assumption: The simulator is idle. (simif::done() == true)
        // Invokes all tasks that wish to be executed on our current target cycle
        void run_scheduled_tasks();
        uint64_t max_cycles = -1;
        // As above, assumes the simulator is idle.
        bool has_timed_out() { return current_cycle == max_cycles; };

    private:
        uint64_t default_step_size = MAX_MIDAS_STEP;
        uint64_t current_cycle = 0;
        std::vector<task_tuple_t> tasks;
};
#endif // __SYSTEMATIC_SCHEDULER_H
