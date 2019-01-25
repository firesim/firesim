#include <queue>

#include <sys/ioctl.h>
#include <linux/if.h>
#include <linux/if_tun.h>

#define NET_IP_ALIGN 2
#define ETH_MAX_BYTES 1518
#define ETH_MAX_WORDS ((ETH_MAX_BYTES + (FLIT_SIZE_BYTES - 1)) / FLIT_SIZE_BYTES) // Round up based on flit size
#define ETH_EXTRA_FLITS 10 // Arbitrary amount of padding (in flits)

/* The other side of this port is a TAP interface to the host network. 
 * This allows users to ssh into a simulated cluster */
class SSHPort : public BasePort {
    public:
        SSHPort(int portNo);
        ~SSHPort();
        void tick();
        void tick_pre();
        void send();
        void recv();
    private:
        int sshtapfd;
        uint8_t* tap_send_buffer;
        uint8_t* tap_recv_buffer;
        void *tap_send_frame = ((char *) tap_send_buffer) + NET_IP_ALIGN;
        void *tap_recv_frame = ((char *) tap_recv_buffer) + NET_IP_ALIGN;
        int tap_send_idx = 0, tap_len;
        bool tap_can_send = false;
        std::queue<NetworkFlit*> out_flits;
        std::queue<NetworkFlit*> in_flits;
};

/* open TAP device */
static int tuntap_alloc(const char *dev, int flags) {
    struct ifreq ifr;
    int tapfd, err;

    if ((tapfd = open("/dev/net/tun", O_RDWR | O_NONBLOCK)) < 0) {
        perror("open()");
        return tapfd;
    }

    memset(&ifr, 0, sizeof(ifr));
    ifr.ifr_flags = flags;
    strncpy(ifr.ifr_name, dev, IFNAMSIZ);

    if ((err = ioctl(tapfd, TUNSETIFF, &ifr)) < 0) {
        perror("ioctl()");
        close(tapfd);
        return err;
    }

    return tapfd;
}

#define DEVNAME_BYTES 128
#define ceil_div(n, d) (((n) - 1) / (d) + 1)

SSHPort::SSHPort(int portNo) : BasePort(portNo, false) {
    //printf("SSHPort: Construct\n");
    char * slotid = NULL; // placeholder for multiple SSH port support if we need it later
    char devname[DEVNAME_BYTES+1];
    devname[0] = '\0';
    strncat(devname, "tap", DEVNAME_BYTES);

    if (!slotid) {
        fprintf(stderr, "Slot ID not specified. Assuming tap0\n");
        slotid = (char*) "0";
    }
    strncat(devname, slotid, DEVNAME_BYTES-3);

    sshtapfd = tuntap_alloc(devname, IFF_TAP | IFF_NO_PI);
    if (sshtapfd < 0) {
        fprintf(stderr, "Could not open tap interface %s\n", devname);
        abort();
    }

    tap_send_buffer = (uint8_t*) malloc( FLIT_SIZE_BYTES*ETH_MAX_WORDS*sizeof(uint8_t) );
    tap_recv_buffer = (uint8_t*) malloc( FLIT_SIZE_BYTES*ETH_MAX_WORDS*sizeof(uint8_t) );
    current_input_buf = (uint8_t*) calloc(sizeof(uint8_t), BUFSIZE_BYTES);
    current_output_buf = (uint8_t*) calloc(sizeof(uint8_t), BUFSIZE_BYTES);
}

SSHPort::~SSHPort() {
    //printf("SSHPort: Deconstruct\n");
    free(tap_send_buffer);
    free(tap_recv_buffer);
}

void SSHPort::send() {
    //printf("SSHPort: Send\n");
    // here, we take data that was written to the port by the switch
    // (data is in current_output_buf)
    // and push it into queues to send into the TAP

    if (((uint64_t*)current_output_buf)[0] == 0xDEADBEEFDEADBEEFL) {
        // if compress flag is set, clear it, this port type doesn't care
        // (and in fact, we're writing too much, so stuff later will get confused)
        ((uint64_t*)current_output_buf)[0] = 0L;
    }

    // first, push into out_flits queue
    for (int tokenno = 0; tokenno < NUM_TOKENS; tokenno++) {
        if (is_valid_flit(current_output_buf, tokenno)) {
            // put data into out_flit queue
            NetworkFlit* flt = new NetworkFlit;
            memcpy(flt->data_buffer, get_flit(current_output_buf, tokenno), FLIT_SIZE_BYTES);
            flt->last = is_last_flit(current_output_buf, tokenno);
            out_flits.push(flt);
        }
    }

    // then, actually send stuff out on the TAP
    // next, see if there is data to send
    if (!tap_can_send) {
        while (!out_flits.empty()) {
            memcpy(tap_send_buffer + (tap_send_idx*FLIT_SIZE_BYTES), out_flits.front()->data_buffer, FLIT_SIZE_BYTES);
            tap_can_send = out_flits.front()->last;
            free(out_flits.front());
            out_flits.pop();
            tap_send_idx++;
            if (tap_can_send)
                break;
        }
    }

    if (tap_can_send) {
        tap_len = (tap_send_idx * FLIT_SIZE_BYTES) - NET_IP_ALIGN;
        if (::write(sshtapfd, tap_send_frame, tap_len) >= 0) {
            tap_send_idx = 0;
            tap_can_send = false;
        } else if (errno != EAGAIN) {
            perror("send()");
            abort();
        }
    }

    // finally, clear current_output_buf for the next iter
    memset(current_output_buf, 0x0, BUFSIZE_BYTES);
}

void SSHPort::recv() {
    //printf("SSHPort: Recv\n");
    // clear the input buf leftover from previous cycle
    memset(current_input_buf, 0x0, BUFSIZE_BYTES);

    // pull in flits from the TAP
    tap_len = ::read(sshtapfd, tap_recv_frame, ETH_MAX_BYTES);
    if (tap_len >= 0) {
        int i, n = ceil_div(tap_len + NET_IP_ALIGN, FLIT_SIZE_BYTES);
        for (i = 0; i < n; i++) {
            NetworkFlit* flt = new NetworkFlit;
            memcpy(flt->data_buffer, tap_recv_buffer + (i * FLIT_SIZE_BYTES), FLIT_SIZE_BYTES);
            flt->last = i == (n - 1);
            in_flits.push(flt);
        }
    } else if (errno != EAGAIN) {
        perror("recv()");
        abort();
    }

    // next, pull off of in_flits until current_input_buf is full, or we have nothing
    // left to write

    for (int tokenno = 0; tokenno < NUM_TOKENS; tokenno++) {
        if (!in_flits.empty()) {
            write_last_flit(current_input_buf, tokenno, in_flits.front()->last);
            write_valid_flit(current_input_buf, tokenno);
            write_flit(current_input_buf, tokenno, in_flits.front()->data_buffer);
            free(in_flits.front());
            in_flits.pop();
        }
    }
}

void SSHPort::tick() {
    // don't need to do anything for SSHPorts
}

void SSHPort::tick_pre() {
    // don't need to do anything for SSHPorts
}
