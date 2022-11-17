// See LICENSE for license details
#ifndef __BLOCKDEV_H
#define __BLOCKDEV_H

#include <queue>
#include <stdio.h>
#include <vector>

#include "bridges/bridge_driver.h"

#define SECTOR_SIZE 512
#define SECTOR_SHIFT 9
#define SECTOR_BEATS (SECTOR_SIZE / 8)
#define MAX_REQ_LEN 16

struct blkdev_request {
  bool write;
  uint32_t offset;
  uint32_t len;
  uint32_t tag;
};

struct blkdev_data {
  uint64_t data;
  uint32_t tag;
};

struct blkdev_write_tracker {
  uint64_t offset;
  uint64_t count;
  uint64_t size;
  uint64_t data[MAX_REQ_LEN * SECTOR_BEATS];
};

#ifdef BLOCKDEVBRIDGEMODULE_struct_guard
class blockdev_t : public bridge_driver_t {
public:
  blockdev_t(simif_t *sim,
             const std::vector<std::string> &args,
             uint32_t num_trackers,
             uint32_t latency_bits,
             BLOCKDEVBRIDGEMODULE_struct *mmio_addrs,
             int blkdevno);
  ~blockdev_t();

  uint32_t nsectors(void) { return _nsectors; }
  uint32_t max_request_length(void) { return MAX_REQ_LEN; }

  void send();
  void recv();
  virtual void init();
  virtual void tick();
  virtual bool terminate() { return false; }
  virtual int exit_code() { return 0; }
  virtual void finish(){};

private:
  BLOCKDEVBRIDGEMODULE_struct *mmio_addrs;

  // Set if, on the previous tick, we couldn't write back all of our response
  // data
  bool resp_data_pending = false;

  uint32_t _ntags;
  uint32_t _nsectors;
  FILE *_file, *logfile;
  char *filename = NULL;
  std::queue<blkdev_request> requests;
  std::queue<blkdev_data> req_data;
  std::queue<blkdev_data> read_responses;
  std::queue<uint32_t> write_acks;

  std::vector<blkdev_write_tracker> write_trackers;

  void do_read(struct blkdev_request &req);
  void do_write(struct blkdev_request &req);
  bool can_accept(struct blkdev_data &data);
  void handle_data(struct blkdev_data &data);
  // Returns true if no widget interaction is required
  bool idle();

  // Default timing model parameters
  uint32_t read_latency = 4096;
  uint32_t write_latency = 4096;
};
#endif // BLOCKDEVBRIDGEMODULE_struct_guard

#endif // __BLOCKDEV_H
