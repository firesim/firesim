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
        if (traceThreads == 0) {
            fprintf(stdout, "TraceDoctor@%d: multithreading disabled, reduce to single buffer depth\n", info.tracerId);
            bufferDepth = 1;
        } else if (traceThreads > workers.size()) {
            traceThreads = workers.size();
            fprintf(stdout, "TraceDoctor@%d: unbalanced thread number, reducing to %d threads\n", info.tracerId, traceThreads);
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
            fprintf(stdout, "TraceDoctor@%d: spawning %u worker threads\n", info.tracerId, traceThreads);
            for (unsigned int i = 0; i < traceThreads; i++) {
                workerThreads.emplace_back(std::move(std::thread(&tracedoctor_t::work, this, i)));
            }
        }
    }
}

tracedoctor_t::~tracedoctor_t() {
    // Cleanup
    if (traceEnabled) {
        exit = true;
        workerQueueCond.notify_all();
        // Join in the worker threads, they will finish processing the last tokens
        for (auto &t : workerThreads) {
            t.join();
        }
        // Explicitly destruct our workers here
        workers.clear();
        for (auto &b: buffers) {
            free(b->data);
        }
        fprintf(stdout, "TraceDoctor@%d: tick_time(%f), dma_time(%f)\n", info.tracerId, tickTime.count(), dmaTime.count());
        fprintf(stdout, "TraceDoctor@%d: traced_tokens(%ld), traced_bytes(%ld)\n", info.tracerId, totalTokens, totalTokens * info.traceBytes);
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


void tracedoctor_t::work(unsigned int thread_index) {
    while (true) {
        // We need this to synchronize the worker threads taking and processing
        // the jobs in order in regard to every worker
        Lock2<locktype_t, locktype_t> workerSync(workerQueueLock, workerSyncLock);
        workerQueueCond.wait(workerSync, [this](){return exit || !workerQueue.empty();});
        if (workerQueue.empty()) {
            return;
        }
        // Pick up a job and its worker
        auto job = workerQueue.front();
        workerQueue.pop();
        // Unlock the workerQueueLock, the main thread might now
        // put new jobs into the queue, however no other thread
        // is yet allowed to take another job out of it
        workerSync.unlock_1();

        // Pick up the worker and takes its ownership
        auto &worker = job.first;
        worker->lock.lock();
        // From here on it is save to release the other threads
        // as no other thread can now execute a job on this worker
        workerSync.unlock_2();

        auto &buffer = job.second;
        worker->worker->tick(buffer->data, buffer->tokens);
        // Release the ownership of this worker so that the next buffer
        // can be given to it by the next worker thread
        worker->lock.unlock();

        // Decrement the buffer reference and if it is not referenced anymore
        // put it back to the spare buffers
        buffer->refs--;
    }
}



bool tracedoctor_t::process_tokens(unsigned int const tokens, bool flush) {
  if (tokens == 0 && !flush)
    return false;

  auto &buffer = buffers[bufferIndex];
  unsigned int tokensReceived = 0;

  // Only if multi threading is enabled, check if buffer is still used
  while (traceThreads && buffer->refs > 0);

  // This buffer must have been processed as refs is 0
  // and it holds tokens that exceed the bufferTokenThreshold
  // Reset it and reuse it
  if (buffer->tokens > bufferTokenThreshold) {
    buffer->tokens = 0;
  }

  // Drain the tokens from the DMA
  {
    auto start = std::chrono::high_resolution_clock::now();
    auto bytesReceived = pull(streamIdx,
                              buffer->data + (buffer->tokens * info.tokenBytes),
                              tokens * info.tokenBytes,
                              (flush) ? 0 : (tokens * info.tokenBytes));
    dmaTime += std::chrono::high_resolution_clock::now() - start;

    tokensReceived = bytesReceived / info.tokenBytes;
  }

  buffer->tokens += tokensReceived;

  // If we have exceeded the bufferTokenThreshold we cannot fit another drain
  // into this buffer and we should process it (normal usage that means it is full)
  if (buffer->tokens > bufferTokenThreshold || flush) {
    if (traceThreads) {
      buffer->refs = workers.size();

      workerQueueLock.lock();
      for (auto &worker : workers) {
        workerQueue.push(std::make_pair(worker, buffer));
      }
      workerQueueLock.unlock();
      workerQueueCond.notify_all();

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
