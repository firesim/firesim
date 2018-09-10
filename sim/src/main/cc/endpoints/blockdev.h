#ifndef __BLOCKDEV_H
#define __BLOCKDEV_H

#include <vector>
#include <queue>
#include <stdio.h>

#include "endpoints/endpoint.h"

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

class blockdev_t: public endpoint_t
{
    public:
        blockdev_t(simif_t* sim, char* filename);
        ~blockdev_t();

        uint32_t nsectors(void) { return _nsectors; }
        uint32_t max_request_length(void) { return MAX_REQ_LEN; }

        void send();
        void recv();
        virtual void init();
        virtual void tick();
        virtual bool terminate() { return false; }

    private:
        bool a_req_valid;
        bool a_req_ready;
        bool a_data_valid;
        bool a_data_ready;
        bool a_resp_valid;
        bool a_resp_ready;

        simif_t* sim;
        uint32_t _ntags;
        uint32_t _nsectors;
        FILE *_file;
        char * filename;
        std::queue<blkdev_request> requests;
        std::queue<blkdev_data> req_data;
        std::queue<blkdev_data> responses;
        std::vector<blkdev_write_tracker> write_trackers;

        void do_read(struct blkdev_request &req);
        void do_write(struct blkdev_request &req);
        bool can_accept(struct blkdev_data &data);
        void handle_data(struct blkdev_data &data);
};

#endif // __BLOCKDEV_H
