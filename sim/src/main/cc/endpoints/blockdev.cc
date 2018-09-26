#include "blockdev.h"
#include <stdio.h>
#include <string.h>

/* Block Device Endpoint Driver
 *
 * This works in conjunction with
 * firechip/testchipip/src/main/scala/BlockDevice.scala (Block Device RTL)
 * and
 * src/main/scala/endpoints/BlockDevWidget.scala
 */

/* Uncomment to get DEBUG printing
 * TODO: better logging mechanism so that we don't need this */
//#define BLKDEV_DEBUG

/* Block Dev software driver constructor.
 * Setup software driver state:
 * Check if we have been given a file to use as a disk, record size and
 * number of sectors to pass to widget */
blockdev_t::blockdev_t(simif_t* sim, char* fname): endpoint_t(sim) {
    // TODO: what is ntags?
    _ntags = 1; // TODO set this automatically
    long size;
    filename = fname;

    if (filename) {
        _file = fopen(filename, "r+");
        if (!_file) {
            fprintf(stderr, "Could not open %s\n", filename);
            abort();
        }
        if (fseek(_file, 0, SEEK_END)) {
            perror("fseek");
            abort();
        }
        size = ftell(_file);
        if (size < 0) {
            perror("ftell");
            abort();
        }
    } else {
        size = 0;
    }
    _nsectors = size >> SECTOR_SHIFT;

    write_trackers.resize(_ntags);
}

blockdev_t::~blockdev_t() {
    if (filename) {
        fclose(_file);
    }
}

/* "init" for blockdev widget that gets called right before target_reset.
 * Here, we set control regs e.g. for # sectors, allowed request length
 * at boot */
void blockdev_t::init() {
#ifdef BLOCKDEVWIDGET_0
    // setup blk dev widget
    write(BLOCKDEVWIDGET_0(bdev_nsectors), nsectors());
    write(BLOCKDEVWIDGET_0(bdev_max_req_len), max_request_length());
#endif // #ifdef BLOCKDEVWIDGET_0
}

/* Take a read request, get data from the disk file, and fill the beats
 * into the response queue from which data will be written to the block device
 * widget on the FPGA */
void blockdev_t::do_read(struct blkdev_request &req) {
    uint64_t offset, nbeats;
    uint64_t blk_data[MAX_REQ_LEN * SECTOR_BEATS];

    offset = req.offset;
    offset <<= SECTOR_SHIFT;
    nbeats = req.len;
    nbeats *= SECTOR_BEATS;

    /* Check that the request is valid. */
    if ((req.offset + req.len) > nsectors()) {
        fprintf(stderr, "Read range %u - %u out of bounds\n",
                req.offset, req.offset + req.len);
        abort();
    }
    if (req.len == 0) {
        fprintf(stderr, "Read request cannot have 0 length\n");
        abort();
    }
    if (req.len > MAX_REQ_LEN) {
        fprintf(stderr, "Read request length too large: %u > %u\n",
                req.len, MAX_REQ_LEN);
        abort();
    }
    if (req.tag >= _ntags) {
        fprintf(stderr, "Read request tag %d too large.\n", req.tag);
        abort();
    }

    /* Seek to correct place in the file. */
    if (fseek(_file, offset, SEEK_SET)) {
        fprintf(stderr, "Could not seek to %llx\n", offset);
        abort();
    }

    /* Perform the read from file. */
    if (fread(blk_data, SECTOR_SIZE, req.len, _file) < req.len) {
        fprintf(stderr, "Cannot read data at %llx\n", offset);
        abort();
    }

    /* Populate response queue from data that has been read from file. Response
     * queue will be consumed when writing to FPGA. */
    for (uint64_t i = 0; i < nbeats; i++) {
        struct blkdev_data resp;
        resp.data = blk_data[i];
        resp.tag = req.tag;
        responses.push(resp);
    }
}

/* Take a write request and set up a write_tracker to process it.
 * Later, handle_data will be called to actually perform the writes
 * to file. */
void blockdev_t::do_write(struct blkdev_request &req) {
    if (req.tag >= _ntags) {
        /* Check that req.tag is in range.
         * This check must happen before we index into write_trackers */
        fprintf(stderr, "Write request tag %d too large.\n", req.tag);
        abort();
    }

    struct blkdev_write_tracker &tracker = write_trackers[req.tag];

    /* Check request sanity */
    if ((req.offset + req.len) > nsectors()) {
        fprintf(stderr, "Write range %u - %u out of bounds\n",
                req.offset, req.offset + req.len);
        abort();
    }
    if (req.len == 0) {
        fprintf(stderr, "Write request cannot have 0 length\n");
        abort();
    }
    if (req.len > MAX_REQ_LEN) {
        fprintf(stderr, "Write request too large: %u > %u\n",
                req.len, MAX_REQ_LEN);
        abort();
    }

    /* Setup tracker state */
    tracker.offset = req.offset;
    tracker.offset *= SECTOR_SIZE;
    tracker.count = 0;
    tracker.size = req.len;
    tracker.size *= SECTOR_BEATS;
}

/* Confirm that a write_tracker has been setup for a chunk of data that
 * we have received from the block device widget, to be written to file */
bool blockdev_t::can_accept(struct blkdev_data &data) {
    return write_trackers[data.tag].size > 0;
}

void blockdev_t::handle_data(struct blkdev_data &data) {
    if (data.tag >= _ntags) {
        /* Check that data.tag is in range.
         * This check must happen before we index into write_trackers */
        fprintf(stderr, "Data tag %d too large.\n", data.tag);
        abort();
    }

    struct blkdev_write_tracker &tracker = write_trackers[data.tag];
    struct blkdev_data resp;

    /* Copy data into the write tracker */
    tracker.data[tracker.count] = data.data;
    tracker.count++;

    if (tracker.count < tracker.size) {
        /* We are still waiting to receive all the data for this write
         * request, so return. */
        return;
    }

    /* Seek to the right place to begin the write to file. */
    if (fseek(_file, tracker.offset, SEEK_SET)) {
        fprintf(stderr, "Could not seek to %llx\n", tracker.offset);
        abort();
    }

    /* Perform the write to file. */
    if (fwrite(tracker.data, sizeof(uint64_t), tracker.count, _file) < tracker.count) {
        fprintf(stderr, "Cannot write data at %llx\n", tracker.offset);
        abort();
    }

    /* Clear the tracker state */
    tracker.offset = 0;
    tracker.count = 0;
    tracker.size = 0;

    /* Send an ack to the block device.
     * TODO: should a block device do this? */
    resp.data = 0;
    resp.tag = data.tag;
    responses.push(resp);
}

/* Read state of the blockdev widget for this "cycle".
 * Called first during each tick */
void blockdev_t::recv() {
#ifdef BLOCKDEVWIDGET_0
    /* Check if there is a request coming from the target that should be
     * processed */
    a_req_valid = read(BLOCKDEVWIDGET_0(bdev_req_valid));
    if (a_req_valid) {
        /* Take a request from the FPGA and put it in SW processing queues */
        struct blkdev_request req;
        req.write = read(BLOCKDEVWIDGET_0(bdev_req_write));
        req.offset = read(BLOCKDEVWIDGET_0(bdev_req_offset));
        req.len = read(BLOCKDEVWIDGET_0(bdev_req_len));
        req.tag = read(BLOCKDEVWIDGET_0(bdev_req_tag));
        requests.push(req);
#ifdef BLKDEV_DEBUG
        fprintf(stderr, "[disk] got req. write %x, offset %x, len %x, tag %x\n",
                req.write, req.offset, req.len, req.tag);
#endif
        a_req_ready = true;
    } else {
        /* no request from target */
        a_req_ready = false;
    }

    /* Check if there is data coming from the target that should be
     * processed */
    a_data_valid = read(BLOCKDEVWIDGET_0(bdev_data_valid));
    if (a_data_valid) {
        /* Take a data chunk from the FPGA and put it in SW processing queues */
        struct blkdev_data data;
        data.data = (((uint64_t)read(BLOCKDEVWIDGET_0(bdev_data_data_upper))) << 32)
            | (read(BLOCKDEVWIDGET_0(bdev_data_data_lower)) & 0xFFFFFFFF);
        data.tag = read(BLOCKDEVWIDGET_0(bdev_data_tag));
#ifdef BLKDEV_DEBUG
        fprintf(stderr, "[disk] got data. data %llx, tag %x\n", data.data, data.tag);
#endif
        req_data.push(data);
        a_data_ready = true;
    } else {
        /* No data from target */
        a_data_ready = false;
    }
#endif // #ifdef BLOCKDEVWIDGET_0
}

/* Write responses for this cycle to the block device widget, set readys to
 * indicate data we consumed from the block device widget this cycle */
void blockdev_t::send() {
#ifdef BLOCKDEVWIDGET_0
    /* Write readies for request/data coming from the FPGA, if we consumed
     * something
     * TODO: don't do these writes if a_req_ready,a_data_ready are not true?
     */
    write(BLOCKDEVWIDGET_0(bdev_req_ready), a_req_ready);
    write(BLOCKDEVWIDGET_0(bdev_data_ready), a_data_ready);

    /* If the block device widget is ready to accept data and we have available
     * responses, send one to the FPGA */
    a_resp_ready = read(BLOCKDEVWIDGET_0(bdev_resp_ready));
    if (!responses.empty() && a_resp_ready) {
        /* Send a response to the FPGA */
        a_resp_valid = true;
        struct blkdev_data resp;
        resp = responses.front();
        write(BLOCKDEVWIDGET_0(bdev_resp_data_upper), (resp.data >> 32) & 0xFFFFFFFF);
        write(BLOCKDEVWIDGET_0(bdev_resp_data_lower), resp.data & 0xFFFFFFFF);
        write(BLOCKDEVWIDGET_0(bdev_resp_tag), resp.tag);
        write(BLOCKDEVWIDGET_0(bdev_resp_valid), a_resp_valid);
#ifdef BLKDEV_DEBUG
        fprintf(stderr, "[disk] sending resp. data %llx, tag %x\n", resp.data, resp.tag);
#endif
        responses.pop();
    } else {
        /* No response to send to the FPGA */
        a_resp_valid = false;
    }
#endif // #ifdef BLOCKDEVWIDGET_0
}

/* This method is called to run a "cycle" of the block dev:
 * 1) Read information from the block device widget
 * 2) Do software processing
 * 3) Write responses to the block device widget */
void blockdev_t::tick() {
    a_req_valid = false;
    a_req_ready = false;
    a_data_valid = false;
    a_data_ready = false;
    a_resp_valid = false;
    a_resp_ready = false;

    do {
        /* Read state of block device widget */
        this->recv();

        /* Do software processing of request queues. (requests coming from the
         * block dev widget) */
        while (!requests.empty()) {
            struct blkdev_request &req = requests.front();
            if (req.write) {
                /* if write request, setup a write tracker */
                do_write(req);
            } else {
                /* if read request, perform read from file and put data into
                 * responses queue. */
                do_read(req);
            }
            requests.pop();
        }

        /* Do software processing of write data queues. (data coming from the
         * block dev widget).
         *
         * If there is data in req_data (from the FPGA) and a tracker has been
         * properly setup for this write, then call handle_data for this beat
         * of the write. */
        while (!req_data.empty() && can_accept(req_data.front())) {
            handle_data(req_data.front());
            req_data.pop();
        }

        /* Write state back to block device widget */
        this->send();

        /* While: if we did any work during this iteration, do another iter */
    } while((a_req_valid && a_req_ready) || (a_data_valid && a_data_ready) || (a_resp_valid && a_resp_ready));
}
