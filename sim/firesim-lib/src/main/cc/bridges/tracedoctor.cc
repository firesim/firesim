//See LICENSE for license details
#ifdef TRACEDOCTORBRIDGEMODULE_struct_guard

#include "tracedoctor.h"

#include <stdio.h>
#include <string.h>
#include <limits.h>
#include <stdexcept>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include <sys/mman.h>

tracedoctor_t::tracedoctor_t(
    simif_t *sim,
    std::vector<std::string> &args,
    TRACEDOCTORBRIDGEMODULE_struct * mmioAddrs,
    const int stream_idx,
    const int stream_depth,
    const unsigned int tokenWidth,
    const unsigned int traceWidth,
    const char* const  clock_domain_name,
    const unsigned int clock_multiplier,
    const unsigned int clock_divisor,
    int tracerId) :
        bridge_driver_t(sim),
        mmioAddrs(mmioAddrs),
        streamIdx(stream_idx),
        streamDepth(stream_depth),
        clock_info(clock_domain_name, clock_multiplier, clock_divisor)
        {
    info.tracerId = tracerId;
    info.tokenBits = tokenWidth;
    info.traceBits = traceWidth;
    info.tokenBytes = (tokenWidth + 7) / 8;
    info.traceBytes = (traceWidth + 7) / 8;

    std::string suffix = std::string("=");
    std::string tracetrigger_arg = std::string("+tracedoctor-trigger") + suffix;
    std::string tracethreads_arg = std::string("+tracedoctor-threads") + suffix;
    std::string tracebuffers_arg = std::string("+tracedoctor-buffers") + suffix;
    std::string traceworker_arg  = std::string("+tracedoctor-worker") + suffix;

    for (auto &arg: args) {
        if (arg.find(tracetrigger_arg) == 0) {
            std::string const sarg = arg.substr(tracetrigger_arg.length());
            if (sarg.compare("none") == 0) {
              this->traceTrigger = 0;
            } else if (sarg.compare("tracerv") == 0) {
              this->traceTrigger = 1;
            } else {
              throw std::invalid_argument("TraceDoctor@" + std::to_string(info.tracerId) + " '" + sarg + "' invalid trigger argument, choose 'none' or 'tracerv'");
            }
        }
        if (arg.find(tracebuffers_arg) == 0) {
            auto bufferargs = strSplit(arg.substr(tracebuffers_arg.length()), ",");
            if (bufferargs.size() >= 1) {
                bufferGrouping = std::stoi(bufferargs[0]);
                bufferGrouping = (bufferGrouping <= 0) ? 1 : bufferGrouping;
            }
            if (bufferargs.size() >= 2) {
                bufferDepth = std::stoi(bufferargs[1]);
                bufferDepth = (bufferDepth <= 0) ? 1 : bufferDepth;
            }
        }
        if (arg.find(tracethreads_arg) == 0) {
            std::string const sarg = arg.substr(tracethreads_arg.length());
            traceThreads = std::stol(sarg);
        }
    }
    for (auto &arg: args) {
        if (arg.find(traceworker_arg) == 0) {
            auto workerargs = strSplit(std::string(arg.c_str() + traceworker_arg.length()), ",");
            std::string workername;
            if (workerargs.empty()) {
                throw std::invalid_argument("TraceDoctor@" + std::to_string(info.tracerId) + " invalid worker argument");
            } else {
                workername = workerargs.front();
                workerargs.erase(workerargs.begin());
            }
            auto reg = workerRegister.find(workername);
            if (reg == workerRegister.end()) {
                throw std::invalid_argument("TraceDoctor@" + std::to_string(info.tracerId) + " unknown worker '" + workername + "'");
            }
            fprintf(stdout, "TraceDoctor@%d: adding worker '%s' with args '", info.tracerId, workername.c_str());
            for (auto &a: workerargs) {
                fprintf(stdout, "%s%s", a.c_str(), (&a == &workerargs.back()) ? "" : ", ");
            }
            fprintf(stdout, "'\n");


            std::shared_ptr<protectedWorker> worker = std::make_shared<protectedWorker>();
            worker->worker = reg->second(workerargs, info);
            workers.push_back(worker);
            workQueues.push_back(std::queue<std::shared_ptr<referencedBuffer>>());
        }
    }

    if (workers.size() == 0) {
        fprintf(stdout, "TraceDoctor@%d: no workers selected, disable tracing\n", info.tracerId);
        traceEnabled = false;
    } else {
        traceEnabled = true;
    }


    // How many tokens are fitting into our buffers
    bufferTokenCapacity = bufferGrouping * streamDepth;
    // At which point the buffer cannot fit another drain (worst case)
    bufferTokenThreshold = bufferTokenCapacity - streamDepth;


    if (traceEnabled) {
      workQueuesMaybeEmpty = true;
      if (traceThreads < 0) {
        traceThreads = workers.size();
      } else if (traceThreads >= workers.size()) {
        traceThreads = workers.size();
      } else if (traceThreads == 0) {
        fprintf(stdout, "TraceDoctor@%d: multithreading disabled, reduce to single buffer depth\n", info.tracerId);
        bufferDepth = 1;
      }

      for (unsigned int i = 0; i < bufferDepth; i++) {
        std::shared_ptr<referencedBuffer> buffer = std::make_shared<referencedBuffer>();
        buffer->data = (char *) aligned_alloc(sysconf(_SC_PAGESIZE), bufferTokenCapacity * info.tokenBytes);
        if (!buffer->data) {
          throw std::runtime_error("TraceDoctor@" + std::to_string(info.tracerId) + " could not allocate memory buffer");
        }
        buffers.push_back(buffer);
      }

      if (traceThreads > 0) {
        if (traceThreads >= workers.size()) {
          fprintf(stdout, "TraceDoctor@%d: using balanced thread pool of %d threads\n", info.tracerId, traceThreads);
        } else {
          fprintf(stdout, "TraceDoctor@%d: using round-robbing thread pool of %u threads\n", info.tracerId, traceThreads);
        }
        auto const &targetWorkFunc = (traceThreads == workers.size()) ? &tracedoctor_t::balancedWork : &tracedoctor_t::work;
        for (unsigned int i = 0; i < (unsigned int) traceThreads; i++) {
          workerThreads.emplace_back(std::move(std::thread(targetWorkFunc, this, i)));
        }
      }
    }
}

tracedoctor_t::~tracedoctor_t() {
    // Cleanup
    if (traceEnabled) {
      std::unique_lock<locktype_t> queueLock(workQueueLock);
      exit = true;
      queueLock.unlock();

      workQueueCond.notify_all();
      // Join in the worker threads, they will finish processing the last tokens
      for (auto &t : workerThreads) {
        t.join();
      }
      // Explicitly destruct our workers here
      workers.clear();
      for (auto &b: buffers) {
        free(b->data);
      }
      fprintf(stdout, "TraceDoctor@%d: tick_time(%f), traced_tokens(%ld), traced_bytes(%ld)\n", info.tracerId, tickTime.count(), totalTokens, totalTokens * info.traceBytes);
    }
    free(mmioAddrs);
}

void tracedoctor_t::init() {
    if (!traceEnabled) {
      write(mmioAddrs->traceEnable, 0);
      write(mmioAddrs->triggerSelector, 0);
      fprintf(stdout, "TraceDoctor@%d: collection disabled\n", info.tracerId);
    } else {
      write(mmioAddrs->traceEnable, 1);
      write(mmioAddrs->triggerSelector, traceTrigger);
      fprintf(stdout, "TraceDoctor@%d: trigger(%s), stream_depth(%d), token_width(%d), trace_width(%d),\n",
              info.tracerId, (traceTrigger == 0) ? "none" : "tracerv", streamDepth, info.tokenBits, info.traceBits);
      fprintf(stdout, "TraceDoctor@%d: buffer_grouping(%d), buffer_depth(%d), workers(%ld), threads(%d)\n", info.tracerId, bufferGrouping, bufferDepth, workers.size(), traceThreads);
    }
    write(mmioAddrs->initDone, true);
}


// The easiest way of distributing work, one thread has one worker
void tracedoctor_t::balancedWork(unsigned int const threadId) {
  if (threadId >= workQueues.size())
    return;

  auto &myWorker = workers[threadId];
  auto &myWorkQueue = workQueues[threadId];

  while(true) {
    std::unique_lock<locktype_t> queueLock(workQueueLock);
    workQueueCond.wait(queueLock, [&](){return exit || !myWorkQueue.empty();});
    if (myWorkQueue.empty()) {
      return;
    }

    auto &buffer = myWorkQueue.front();
    myWorkQueue.pop();
    queueLock.unlock();

    myWorker->worker->tick(buffer->data, buffer->tokens);
    buffer->refs--;
  }
}

// More complicated, distributing the workers round robbing on the threads
void tracedoctor_t::work(unsigned int const threadId) {
  std::shared_ptr<referencedBuffer> buffer(nullptr);
  std::shared_ptr<protectedWorker> worker(nullptr);
  unsigned int const numWorkQueues = workQueues.size();
  unsigned int robbingId = threadId % numWorkQueues;
  bool foundJob = false;

  while (true) {
    // Condition on the queue, we get notified either on exit or when a job is inserted
    std::unique_lock<locktype_t> queueLock(workQueueLock);
    workQueueCond.wait(queueLock, [this, &foundJob](){ return exit || foundJob || !workQueuesMaybeEmpty; });
    if (workQueuesMaybeEmpty && !foundJob) {
      return;
    }

    foundJob = false;

    // Roung robbing through the work queues to find a next job
    for (unsigned int i = 0; i < numWorkQueues; i++) {
      robbingId = (robbingId + 1) % numWorkQueues;
      if (!workQueues[robbingId].empty() && workers[robbingId]->lock.try_lock()) {
        worker = workers[robbingId];
        buffer = workQueues[robbingId].front();
        workQueues[robbingId].pop();
        foundJob = true;
        break;
      }
    }

    // If we couldn't find a job. Either the work queues are empty or other threads
    // are currently working on them. Either way, we cannot help out anymore.
    workQueuesMaybeEmpty = workQueuesMaybeEmpty || !foundJob;
    queueLock.unlock();

    if (foundJob) {
      // Process the worker and buffer
      worker->worker->tick(buffer->data, buffer->tokens);
      worker->lock.unlock();
      // Decrement the buffer references, if zero it will be put to the spare buffers
      buffer->refs--;
    }
  }
}



bool tracedoctor_t::process_tokens(unsigned int const tokens, bool flush) {
  if (tokens == 0 && !flush)
    return false;

  auto &buffer = buffers[bufferIndex];
  unsigned int tokensReceived = 0;

  // Only if multi threading is enabled, check if buffer is still used
  while (traceThreads > 0 && buffer->refs > 0);

  // This buffer must have been processed as refs is 0
  // and it holds tokens that exceed the bufferTokenThreshold
  // Reset it and reuse it
  if (buffer->tokens > bufferTokenThreshold) {
    buffer->tokens = 0;
  }

  // Drain the tokens from the DMA
  {
    auto bytesReceived = pull(streamIdx,
                              buffer->data + (buffer->tokens * info.tokenBytes),
                              tokens * info.tokenBytes,
                              (flush) ? 0 : (tokens * info.tokenBytes));

    tokensReceived = bytesReceived / info.tokenBytes;
  }

  buffer->tokens += tokensReceived;

  // If we have exceeded the bufferTokenThreshold we cannot fit another drain
  // into this buffer and we should process it (normal usage that means it is full)
  if (buffer->tokens > bufferTokenThreshold || (buffer->tokens && flush)) {
    if (traceThreads > 0) {
      buffer->refs = workers.size();

      workQueueLock.lock();
      for (auto &workQueue : workQueues) {
        workQueue.push(buffer);
      }
      workQueuesMaybeEmpty = false;
      workQueueLock.unlock();
      workQueueCond.notify_all();

      // Only for multithreading it makes sense to use multiple buffers
      bufferIndex = (bufferIndex + 1) % buffers.size();
    } else {
      // No threads are launched, processing the workers here
      for (auto &worker: workers) {
        worker->worker->tick(buffer->data, buffer->tokens);
      }
    }
  }

  totalTokens += tokensReceived;

  return tokensReceived > 0;
}


void tracedoctor_t::tick() {
    auto start = std::chrono::high_resolution_clock::now();
    if (traceEnabled)
        process_tokens(streamDepth, false);
    tickTime += std::chrono::high_resolution_clock::now() - start;
}


void tracedoctor_t::flush() {
    if (this->traceEnabled)
      while (process_tokens(streamDepth, true));
}



#endif // TRACEDOCTORBRIDGEMODULE_struct_guard
