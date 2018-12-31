
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
        BasePort(int portNo, bool throttle, int fc_incredits, int fc_updatePeriod);
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

        int fc_unassigned;
        int fc_assigned;
        int fc_available;
        int fc_updatePeriod;
        uint64_t fc_lastUpdate;

        int push_input(switchpacket *sp);
        switchpacket *pop_input(void);

    protected:
        int _portNo;
        bool _throttle;
};

BasePort::BasePort(int portNo, bool throttle, int fc_incredits, int fc_updatePeriod)
    : _portNo(portNo), _throttle(throttle)
{
    this->fc_unassigned = fc_incredits;
    this->fc_assigned = 0;
    this->fc_available = 1;
    this->fc_updatePeriod = fc_updatePeriod;
    this->fc_lastUpdate = 0;

#ifdef CREDIT_FLOWCONTROL
    if (fc_updatePeriod > 0)
        this->fc_available = 0;
#endif
}

int BasePort::push_input(switchpacket *sp)
{
#ifdef CREDIT_FLOWCONTROL
    // Check whether or not this is a credit update packet
    // If so, update fc_available but don't add the packet to the input queue
    uint16_t cred_update = sp->dat[0] & 0xffff;
    if (sp->amtwritten == 1 && cred_update != 0) {
        printf("port %d: got credit update %d @ %ld\n",
                        _portNo, cred_update, sp->timestamp);
        fc_available += cred_update;
        free(sp);
        return 0;
    }
#endif
    inputqueue.push(sp);
#ifdef CREDIT_FLOWCONTROL
    if (fc_updatePeriod > 0)
        this->fc_assigned--;
#endif
    return 1;
}

switchpacket *BasePort::pop_input(void)
{
    switchpacket * sp = inputqueue.front();
    inputqueue.pop();

#ifdef CREDIT_FLOWCONTROL
    if (fc_updatePeriod > 0)
        this->fc_unassigned++;
#endif

    return sp;
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

    bool empty_buf = true;

#ifdef CREDIT_FLOWCONTROL
    uint64_t timeElapsed = maxtime - fc_lastUpdate;
    if (fc_updatePeriod > 0 && fc_unassigned > 0 && timeElapsed > fc_updatePeriod) {
        switchpacket *cup = (switchpacket *) malloc(sizeof(switchpacket));
        cup->timestamp = maxtime - 1;
        cup->dat[0] = fc_unassigned;
        cup->amtwritten = 1;
        cup->amtread = 0;
        cup->sender = -1;
        outputqueue.push(cup);

        printf("port %d send credit update %d @ %ld\n",
                _portNo, fc_unassigned, cup->timestamp);
        fc_assigned += fc_unassigned;
        fc_unassigned = 0;
        fc_lastUpdate = maxtime;
    }
#endif

    while (!(outputqueue.empty())) {
        switchpacket *thispacket = outputqueue.front();
        // first, check timing boundaries.
        uint64_t space_available = LINKLATENCY - flitswritten;
        uint64_t outputtimestamp = thispacket->timestamp;
        uint64_t outputtimestampend = outputtimestamp + thispacket->amtwritten;

        // confirm that a) we are allowed to send this out based on timestamp
        // b) we are allowed to send this out based on available space (TODO fix)
        if (fc_available > 0 && outputtimestamp < maxtime) {
#ifdef LIMITED_BUFSIZE
            // output-buffer size-based throttling, based on input time of first flit
            int64_t diff = basetime + flitswritten - outputtimestamp;
            if ((thispacket->amt_read == 0) && (diff > OUTPUT_BUF_SIZE)) {
                // this packet would've been dropped due to buffer overflow.
                // so, drop it.
                printf("overflow, drop pack: intended timestamp: %ld, current timestamp: %ld, out bufsize in # flits: %ld, diff: %ld\n", outputtimestamp, basetime + flitswritten, OUTPUT_BUF_SIZE, (int64_t)(basetime + flitswritten) - (int64_t)(outputtimestamp));
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

            int i = thispacket->amtread;
            if (i == 0) {
                //printf("intended timestamp: %ld, actual timestamp: %ld, diff %ld\n", 
                //        outputtimestamp, basetime + flitswritten, 
                //        (int64_t)(basetime + flitswritten) - (int64_t)(outputtimestamp));
                printf("packet timestamp: %ld, len: %ld, receiver: %d\n",
                        basetime + flitswritten, thispacket->amtwritten, _portNo);
            }
            for (;(i < thispacket->amtwritten) && (flitswritten < LINKLATENCY); i++) {
                write_last_flit(current_output_buf, flitswritten, i == (thispacket->amtwritten-1));
                write_valid_flit(current_output_buf, flitswritten);
                write_flit(current_output_buf, flitswritten, thispacket->dat[i]);
                empty_buf = false;

                if (!_throttle)
                    flitswritten++;
                else if ((i + 1) % throttle_numer == 0)
                    flitswritten += (throttle_denom - throttle_numer + 1);
                else
                    flitswritten++;
            }
            if (i == thispacket->amtwritten) {
                // we finished sending this packet, so get rid of it
                outputqueue.pop();
                free(thispacket);
#ifdef CREDIT_FLOWCONTROL
	        // Don't decrement FC counter for FC update packet
                if (fc_updatePeriod > 0 && thispacket->sender != -1)
                    fc_available--;
#endif
            } else {
                // we're not done sending this packet, so mark how much has been sent
                // for the next time
                thispacket->amtread = i;
                break;
            }
        } else {
            // since otuput queue is sorted on time, we have nothing else to
            // write
            break;
        }
    }
    if (empty_buf) {
        ((uint64_t*)current_output_buf)[0] = 0xDEADBEEFDEADBEEFL;
    }
}

// initialize output port fullness for this round
void BasePort::setup_send_buf() {
    for (int bigtokenno = 0; bigtokenno < NUM_BIGTOKENS; bigtokenno++) {
        *((uint64_t*)(current_output_buf) + bigtokenno*8) = 0L;
    }
}
