// See LICENSE for license details.

#ifndef __BRIDGES_BRIDGE_STREAM_DRIVER_H
#define __BRIDGES_BRIDGE_STREAM_DRIVER_H

#include <memory>
#include <vector>

class FPGAToCPUStreamDriver {
public:
  virtual ~FPGAToCPUStreamDriver() = default;
  ;
  virtual void init() = 0;
  virtual size_t pull(void *dest, size_t num_bytes, size_t required_bytes) = 0;
  virtual void flush() = 0;
};

class CPUToFPGAStreamDriver {
public:
  virtual ~CPUToFPGAStreamDriver() = default;
  ;
  virtual void init() = 0;
  virtual size_t push(void *src, size_t num_bytes, size_t required_bytes) = 0;
  virtual void flush() = 0;
};

class StreamEngine {
public:
  /**
   * @brief Initialiases MMIO-related structures.
   */
  void init();

  /**
   * @brief Dequeues num_bytes of data from an FPGA-to-CPU stream
   *
   * Attempts to copy @num_bytes of data from the head of a bridge stream
   * specified by @stream_idx into a destination buffer (@dest) in the
   * process’s memory space. Non-blocking.
   *
   * @param stream_idx Stream index. Assigned at Golden Gate compile time
   * @param dest Destination buffer into which to copy stream data. (Virtual
   * address.)
   * @param num_bytes Number of bytes to copy.
   * @param required_bytes If pull would return less than this many bytes, it
   * returns 0 instead.
   *
   * @returns Number of bytes copied. Can be less than requested.
   *
   */
  size_t pull(unsigned int stream,
              void *dest,
              size_t num_bytes,
              size_t required_bytes);

  /**
   * @brief Enqueues num_bytes of data into a CPU-to-FPGA stream
   *
   * Attempts to copy @num_bytes of data from a source buffer (@src) to the
   * tail of the CPU-to-FPGA bridge stream specified by @stream_idx.
   *
   * @param stream_idx Stream index. Assigned at Golden Gate compile time
   * @param src Source buffer from which to copy stream data.
   * @param num_bytes Number of bytes to copy.
   * @param required_bytes If push would accept less than this many bytes, it
   * accepts 0 instead.
   *
   * @returns Number of bytes copied. Can be less than requested.
   *
   */
  size_t
  push(unsigned int stream, void *src, size_t num_bytes, size_t required_bytes);

  /**
   * @brief Hint that a stream should bypass any underlying batching
   * optimizations.
   *
   * A user-directed hint that a stream should bypass any underlying batching
   * optimizations. This may permit a future pull to read data that may
   * otherwise remain queued in parts of the host.
   *
   * @param stream_no The index of the stream to flush
   */
  void pull_flush(unsigned int stream_no);

  /**
   * @brief Analagous to pull_flush but for CPU-to-FPGA streams
   *
   * @param stream_no The index of the stream to flush
   */
  void push_flush(unsigned int stream_no);

protected:
  std::vector<std::unique_ptr<FPGAToCPUStreamDriver>> fpga_to_cpu_streams;
  std::vector<std::unique_ptr<CPUToFPGAStreamDriver>> cpu_to_fpga_streams;
};

#endif // __BRIDGES_BRIDGE_STREAM_DRIVER_H
