//See LICENSE for license details
#ifndef __TRACEDOCTOR_H_
#define __TRACEDOCTOR_H_

#include "bridges/bridge_driver.h"
#include "bridges/clock_info.h"


#include <vector>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <memory>
#include <queue>
#include <atomic>
#include <chrono>
#include <numeric>
#include <functional>
// Add some more workers
// If you add/remove workers, they must be registered/deregistered in the
// workerRegister map here in the header file in the tracedoctor_t class
#include "tracedoctor_worker.h"
#include "tracedoctor_example.h"

#ifdef TRACEDOCTORBRIDGEMODULE_struct_guard

// Bridge Driver Instantiation Template
#define INSTANTIATE_TRACEDOCTOR(FUNC,IDX) \
     TRACEDOCTORBRIDGEMODULE_ ## IDX ## _substruct_create; \
     FUNC(new tracedoctor_t( \
        this, \
        args, \
        TRACEDOCTORBRIDGEMODULE_ ## IDX ## _substruct, \
        TRACEDOCTORBRIDGEMODULE_ ## IDX ## _to_cpu_stream_idx, \
        TRACEDOCTORBRIDGEMODULE_ ## IDX ## _to_cpu_stream_depth, \
        TRACEDOCTORBRIDGEMODULE_ ## IDX ## _token_width, \
        TRACEDOCTORBRIDGEMODULE_ ## IDX ## _trace_width, \
        TRACEDOCTORBRIDGEMODULE_ ## IDX ## _clock_domain_name, \
        TRACEDOCTORBRIDGEMODULE_ ## IDX ## _clock_multiplier, \
        TRACEDOCTORBRIDGEMODULE_ ## IDX ## _clock_divisor, \
        IDX)); \



// https://stackoverflow.com/questions/7045576/using-more-than-one-mutex-with-a-conditional-variable
template<class T1, class T2> class Lock2 {
    bool own1_;
    bool own2_;
    T1 &m1_;
    T2 &m2_;
public:
    Lock2(T1 &m1, T2 &m2)
        : own1_(false), own2_(false), m1_(m1), m2_(m2)
    {
        lock();
    }

    ~Lock2() {
        unlock();
    }

    Lock2(const Lock2&) = delete;
    Lock2& operator=(const Lock2&) = delete;

    void lock() {
        if (!own1_ && !own2_) {
            own1_=true; own2_=true;
            std::lock(m1_, m2_);
        } else if (!own1_) {
            own1_=true;
            m1_.lock();
        } else if (!own2_) {
            own2_=true;
            m2_.lock();
        }
    }

    void unlock() {
        unlock_1();
        unlock_2();
    }

    void unlock_1() {
        if (own1_) {
            own1_=false;
            m1_.unlock();
        }
    }

    void unlock_2() {
        if (own2_) {
            own2_=false;
            m2_.unlock();
        }
    }
};

class spinlock {
        std::atomic<bool> lock_ = {false};
public:
    void lock() {
        for (;;) {
            if (!lock_.exchange(true, std::memory_order_acquire)) {
                break;
            }
            while (lock_.load(std::memory_order_relaxed));
        }
    }

    void unlock() {
        lock_.store(false, std::memory_order_release);
    }

    bool try_lock() {
        return !lock_.exchange(true, std::memory_order_acquire);
    }
};

// #define locktype_t spinlock
#define locktype_t std::mutex


struct protectedWorker {
    locktype_t lock;
    std::shared_ptr<tracedoctor_worker> worker;
};

struct referencedBuffer {
    char *data;
    unsigned int tokens;
    std::atomic<unsigned int> refs;
};


class tracedoctor_t: public bridge_driver_t
{
public:
    tracedoctor_t(simif_t *sim,
                   std::vector<std::string> &args,
                   TRACEDOCTORBRIDGEMODULE_struct * mmio_addrs,
                   const int stream_idx,
                   const int stream_depth,
                   const unsigned int tokenWidth,
                   const unsigned int traceWidth,
                   const char* const  clock_domain_name,
                   const unsigned int clock_multiplier,
                   const unsigned int clock_divisor,
                   int tracerId);
    ~tracedoctor_t();

    virtual void init();
    virtual void tick();
    virtual bool terminate() { return false; }
    virtual int exit_code() { return 0; }
    virtual void finish() { flush(); };
    virtual void balancedWork(unsigned int const threadIndex);
    virtual void work(unsigned int const threadIndex);

private:
    // Add you workers here:
    std::map<std::string,
             std::function<std::shared_ptr<tracedoctor_worker>(std::vector<std::string> &, struct traceInfo &)>> workerRegister = {
        {"dummy",       [](std::vector<std::string> &args, struct traceInfo &info){
                      (void) args; return std::make_shared<tracedoctor_worker>("Dummy", args, info, TDWORKER_NO_FILES);
                  }},
        {"filer",       [](std::vector<std::string> &args, struct traceInfo &info){
                      return std::make_shared<tracedoctor_filedumper>(args, info);
                  }},
        {"tracerv",     [](std::vector<std::string> &args, struct traceInfo &info){
                      return std::make_shared<tracedoctor_tracerv>(args, info);
                  }},
        {"tracerv_partcsv",     [](std::vector<std::string> &args, struct traceInfo &info){
                      return std::make_shared<tracedoctor_tracerv_partcsv>(args, info);
                  }}
    };

    TRACEDOCTORBRIDGEMODULE_struct * mmioAddrs;
    int streamIdx;
    int streamDepth;

    std::vector<std::thread> workerThreads;
    std::vector<std::shared_ptr<protectedWorker>> workers;
    std::vector<std::shared_ptr<referencedBuffer>> buffers;

    locktype_t workQueueLock;
    std::condition_variable workQueueCond;
    std::vector<std::queue<std::shared_ptr<referencedBuffer>>> workQueues;
    bool workQueuesMaybeEmpty = true;

    unsigned int bufferIndex = 0;
    unsigned int bufferGrouping = 1;
    unsigned int bufferDepth = 1;
    unsigned int bufferTokenCapacity;
    unsigned int bufferTokenThreshold;
    unsigned long int totalTokens = 0;

    std::chrono::duration<double> tickTime = std::chrono::seconds(0);

    ClockInfo clock_info;
    struct traceInfo info = {};

    bool traceEnabled = false;
    unsigned int traceTrigger = 0;
    int traceThreads = -1;
    bool exit = false;

    std::shared_ptr<protectedWorker> getWorker(std::string workername, std::vector<std::string> &args, struct traceInfo &info);
    bool process_tokens(unsigned int const tokens, bool flush = false);
    void flush();
};
#endif // TRACEDOCTORBRIDGEMODULE_struct_guard

#endif // __TRACEDOCTOR_H_
