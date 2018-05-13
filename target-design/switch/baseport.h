
struct switchpacket {
    uint64_t timestamp;
    uint64_t dat[200];
    int amtwritten;
    int amtread;
    int sender;
};

typedef struct switchpacket switchpacket;


class BasePort {
    public:
        BasePort(int portNo);
        void write_flits_to_output();
        virtual void tick() = 0; // some ports need to do management every switching loop
        virtual void tick_pre() = 0; // some ports need to do management every switching loop

        virtual void send() = 0;
        virtual void recv() = 0;
        void setup_send_buf();

        // input/output bufs. ports that do fancy stuff with pointers may
        // need to reassign these every iter of the outermost switching loop
        uint8_t * current_input_buf; // current input buf
        uint8_t * current_output_buf; // current output buf


        int recv_buf_port_map = -1; // used when frame crosses batching boundary. the last port that fed this port's send buf

        switchpacket * input_in_progress = NULL;
        switchpacket * output_in_progress = NULL;

        std::queue<switchpacket*> inputqueue;
        std::queue<switchpacket*> outputqueue;

    protected:
        int _portNo;
};

BasePort::BasePort(int portNo) : _portNo(portNo) {
}

// assumes valid
void BasePort::write_flits_to_output() {
    // 1) assume that outputbuf's valids have been cleared,
    // so if you write nothing, it's the same as no valid input to the
    // thing this port is connected to for that cycle.
    //
    // 2) next, we will go through the output queue, and keep grabbing
    // things off of its front until we can no longer fit them (either due
    // to congestion, crossing a batch boundary (TODO fix this), or timing.


    uint64_t flitswritten = 0;
    uint64_t basetime = this_iter_cycles_start;
    uint64_t maxtime = this_iter_cycles_start + LINKLATENCY;

    while (!(outputqueue.empty())) {
        // first, check timing boundaries.
        uint64_t space_available = LINKLATENCY - flitswritten;
        uint64_t outputtimestamp = outputqueue.front()->timestamp;
        uint64_t outputtimestampend = outputtimestamp + outputqueue.front()->amtwritten;
        
        // confirm that a) we are allowed to send this out based on timestamp
        // b) we are allowed to send this out based on available space (TODO fix)
        if (outputtimestampend < maxtime && (outputqueue.front()->amtwritten <= space_available)) {
#ifdef LIMITED_BUFSIZE
            // output-buffer size-based throttling, based on input time of first flit
            int64_t diff = basetime + flitswritten - outputtimestamp;
            if (diff > OUTPUT_BUF_SIZE) {
                // this packet would've been dropped due to buffer overflow.
                // so, drop it.
                printf("overflow, drop pack: intended timestamp: %ld, current timestamp: %ld, out bufsize in # flits: %ld, diff: %ld\n", outputtimestamp, basetime + flitswritten, OUTPUT_BUF_SIZE, (int64_t)(basetime + flitswritten) - (int64_t)(outputtimestamp));
                switchpacket * thispacket = outputqueue.front();
                outputqueue.pop();
                free(thispacket);
                continue;
            }
#endif
            // we can write this flit
            //
            // first, advance flitswritten to the correct start point:
            uint64_t timestampdiff = outputtimestamp > basetime ? outputtimestamp - basetime : 0L;
            flitswritten = std::max(flitswritten, timestampdiff);
            switchpacket * thispacket = outputqueue.front();
            outputqueue.pop();
            printf("intended timestamp: %ld, actual timestamp: %ld, diff %ld\n", outputtimestamp, basetime + flitswritten, (int64_t)(basetime + flitswritten) - (int64_t)(outputtimestamp));
            for (int i = 0; i < thispacket->amtwritten; i++) {
                write_last_flit(current_output_buf, flitswritten, i == (thispacket->amtwritten-1));
                write_valid_flit(current_output_buf, flitswritten);
                write_flit(current_output_buf, flitswritten, thispacket->dat[i]);
                flitswritten++;
            }
            free(thispacket);
        } else {
            // since otuput queue is sorted on time, we have nothing else to
            // write
            break;
        }
    }
}

// initialize output port fullness for this round
void BasePort::setup_send_buf() {
    for (int bigtokenno = 0; bigtokenno < NUM_BIGTOKENS; bigtokenno++) {
        *((uint64_t*)(current_output_buf) + bigtokenno*8) = 0L;
    }
}
