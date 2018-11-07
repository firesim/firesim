#ifdef SIMPLENICWIDGET_struct_guard

#include "simplenic.h"

#include <stdio.h>
#include <string.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include <sys/mman.h>

// DO NOT MODIFY PARAMS BELOW THIS LINE

// AJG: NOTE THE FREQ OF THE SYSTEM IS ASSUMED TO BE 3.2Ghz
//
// The max bandwidth can be a flit size of 509 since there is 3 extra bits given in the scala code
// this this token size should alway fill up the 512 as much as possible without going over 512.
//
// This makes the max bw be 509 x 3.2ghz =~=(about) 1.6tbps
// to find flit size it should be bwWanted / 3.2 = flitsize
//
// Eq should be
//      tokensperbigtoken = PCIE_WIDTH/(FLIT_SIZE + 3) 
#define PCIE_WIDTH (512)
#define PROC_SPEED (3.2)
#define VAL_BITS (3)
#define EXTRABYTES (1)

static void simplify_frac(int n, int d, int *nn, int *dd)
{
    int a = n, b = d;

    // compute GCD
    while (b > 0) {
        int t = b;
        b = a % b;
        a = t;
    }

    *nn = n / a;
    *dd = d / a;
}

#define niclog_printf(...) if (this->niclog) { fprintf(this->niclog, __VA_ARGS__); fflush(this->niclog); }

simplenic_t::simplenic_t(simif_t *sim, std::vector<std::string> &args,
        SIMPLENICWIDGET_struct *mmio_addrs, int simplenicno): endpoint_t(sim)
{
    this->mmio_addrs = mmio_addrs;

    const char *niclogfile = NULL;
    const char *shmemportname = NULL;
    int netbw = MAX_BANDWIDTH, netburst = 8;

    this->loopback = false;
    this->niclog = NULL;
    this->mac_lendian = 0;
    this->LINKLATENCY = 0;


    // construct arg parsing strings here. We basically append the endpoint
    // number to each of these base strings, to get args like +blkdev0 etc.
    std::string num_equals = std::to_string(simplenicno) + std::string("=");
    std::string niclog_arg = std::string("+niclog") + num_equals;
    std::string nicloopback_arg = std::string("+nic-loopback") + std::to_string(simplenicno);
    std::string macaddr_arg = std::string("+macaddr") + num_equals;
    std::string netbw_arg = std::string("+netbw") + num_equals;
    std::string netburst_arg = std::string("+netburst") + num_equals;
    std::string linklatency_arg = std::string("+linklatency") + num_equals;
    std::string shmemportname_arg = std::string("+shmemportname") + num_equals;


    for (auto &arg: args) {
        if (arg.find(niclog_arg) == 0) {
            niclogfile = const_cast<char*>(arg.c_str()) + niclog_arg.length();
        }
        if (arg.find(nicloopback_arg) == 0) {
            this->loopback = true;
        }
        if (arg.find(macaddr_arg) == 0) {
            uint8_t mac_bytes[6];
            int mac_octets[6];
            char * macstring = NULL;
            macstring = const_cast<char*>(arg.c_str()) + macaddr_arg.length();
            char * trailingjunk;

            // convert mac address from string to 48 bit int
            if (6 == sscanf(macstring, "%x:%x:%x:%x:%x:%x%c",
                        &mac_octets[0], &mac_octets[1], &mac_octets[2],
                        &mac_octets[3], &mac_octets[4], &mac_octets[5],
                        trailingjunk)) {

                for (int i = 0; i < 6; i++) {
                    mac_lendian |= (((uint64_t)(uint8_t)mac_octets[i]) << (8*i));
                }
            } else {
                fprintf(stderr, "INVALID MAC ADDRESS SUPPLIED WITH +macaddrN=\n");
            }
        }
        if (arg.find(netbw_arg) == 0) {
            char *str = const_cast<char*>(arg.c_str()) + netbw_arg.length();
            netbw = atoi(str);
        }
        if (arg.find(netburst_arg) == 0) {
            char *str = const_cast<char*>(arg.c_str()) + netburst_arg.length();
            netburst = atoi(str);
        }
        if (arg.find(linklatency_arg) == 0) {
            char *str = const_cast<char*>(arg.c_str()) + linklatency_arg.length();
            this->LINKLATENCY = atoi(str);
        }
        if (arg.find(shmemportname_arg) == 0) {
            shmemportname = const_cast<char*>(arg.c_str()) + shmemportname_arg.length();
        }
    }

    assert(shmemportname != NULL);
    assert(this->LINKLATENCY > 0);
    assert(netburst < 256);
    //AJG: Let netbw determine the variables
    assert( netbw <= MAX_BANDWIDTH );//(( PCIE_WIDTH - VAL_BITS ) * PROC_SPEED ) );
    // AJG: Might have to make BUFWIDTH be the next smaller power of 2 so that you can simulate correctly (cannot have 509 as a actual datafield... right)
    BUFWIDTH = 64; // currently hardcoded since there is you want to manipulate the sim speed by the rlimiter//netbw / PROC_SPEED;
    TOKENS_PER_BIGTOKEN = PCIE_WIDTH / (BUFWIDTH + VAL_BITS);
    SIMLATENCY_BT = (this->LINKLATENCY) / TOKENS_PER_BIGTOKEN;
    BUFBYTES = SIMLATENCY_BT * BUFWIDTH;
    
    // AJG: Following is used to limit the amount of tokens the nic is given depending on the MAX_BW and the input BW
    //      Note: netburst is the amt of pkts put into a larger PCIE packet (ex. 64bit small flits can fit into 512bits 8 times so netburst can be up to 8)
    simplify_frac(netbw, MAX_BANDWIDTH, &rlimit_inc, &rlimit_period);

    // AJG: Revert above later when you know the actual max
    // What is netburst, if it is the amount of packets to try to fit in a pcie section then this must also be parameterized
    // THE AMOUTN OF PACKETS THAT ARE BEING PUT INTO THE PCIE WIDTH (THIS MUST SCALE WITH THE SIZE OF THE FLIT)
    rlimit_size = netburst;

    printf("using link latency: %d cycles\n", this->LINKLATENCY);
    printf("using netbw: %d\n", netbw);
    printf("using netburst: %d\n", netburst);
    printf("using SIMLATENCY_BT: %d\n", SIMLATENCY_BT);
    printf("using BUFBYTES: %d\n", BUFBYTES);
    printf("using rlimit_inc: %d\n", rlimit_inc);
    printf("using rlimit_period: %d\n", rlimit_period);

    if (niclogfile) {
        this->niclog = fopen(niclogfile, "w");
        if (!this->niclog) {
            fprintf(stderr, "Could not open NIC log file: %s\n", niclogfile);
            abort();
        }
    }

    char name[257];
    int shmemfd;

    if (!loopback) {
        for (int j = 0; j < 2; j++) {
            printf("Using non-slot-id associated shmemportname:\n");
            sprintf(name, "/port_nts%s_%d", shmemportname, j);

            printf("opening/creating shmem region\n%s\n", name);
            shmemfd = shm_open(name, O_RDWR | O_CREAT , S_IRWXU);
            ftruncate(shmemfd, BUFBYTES+EXTRABYTES);
            pcis_read_bufs[j] = (char*)mmap(NULL, BUFBYTES+EXTRABYTES, PROT_READ | PROT_WRITE, MAP_SHARED, shmemfd, 0);

            printf("Using non-slot-id associated shmemportname:\n");
            sprintf(name, "/port_stn%s_%d", shmemportname, j);

            printf("opening/creating shmem region\n%s\n", name);
            shmemfd = shm_open(name, O_RDWR | O_CREAT , S_IRWXU);
            ftruncate(shmemfd, BUFBYTES+EXTRABYTES);
            pcis_write_bufs[j] = (char*)mmap(NULL, BUFBYTES+EXTRABYTES, PROT_READ | PROT_WRITE, MAP_SHARED, shmemfd, 0);
        }
    } else {
        for (int j = 0; j < 2; j++) {
            pcis_read_bufs[j] = (char *) malloc(BUFBYTES + EXTRABYTES);
            pcis_write_bufs[j] = pcis_read_bufs[j];
        }
    }
}

simplenic_t::~simplenic_t() {
    if (this->niclog)
        fclose(this->niclog);
    if (loopback) {
        for (int j = 0; j < 2; j++)
            free(pcis_read_bufs[j]);
    } else {
        for (int j = 0; j < 2; j++) {
            munmap(pcis_read_bufs[j], BUFBYTES+EXTRABYTES);
            munmap(pcis_write_bufs[j], BUFBYTES+EXTRABYTES);
        }
    }
    free(this->mmio_addrs);
}

#define ceil_div(n, d) (((n) - 1) / (d) + 1)

void simplenic_t::init() {
    write(mmio_addrs->macaddr_upper, (mac_lendian >> 32) & 0xFFFF);
    write(mmio_addrs->macaddr_lower, mac_lendian & 0xFFFFFFFF);
    write(mmio_addrs->rlimit_settings,
            (rlimit_inc << 16) | ((rlimit_period - 1) << 8) | rlimit_size);

    uint32_t output_tokens_available = read(mmio_addrs->outgoing_count);
    uint32_t input_token_capacity = SIMLATENCY_BT - read(mmio_addrs->incoming_count);
    if ((input_token_capacity != SIMLATENCY_BT) || (output_tokens_available != 0)) {
        printf("FAIL. INCORRECT TOKENS ON BOOT. produced tokens available %d, input slots available %d\n", output_tokens_available, input_token_capacity);
        exit(1);
    }

    printf("On init, %d token slots available on input.\n", input_token_capacity);
    uint32_t token_bytes_produced = 0;
    token_bytes_produced = push(
            0x0,
            pcis_write_bufs[1],
            BUFWIDTH*input_token_capacity);
    if (token_bytes_produced != input_token_capacity*BUFWIDTH) {
        printf("ERR MISMATCH!\n");
        exit(1);
    }
    return;
}

//#define TOKENVERIFY

// checking for token loss
uint32_t next_token_from_fpga = 0x0;
uint32_t next_token_from_socket = 0x0;

uint64_t iter = 0;

int currentround = 0;
int nextround = 1;

#ifdef TOKENVERIFY
uint64_t timeelapsed_cycles = 0;
#endif

void simplenic_t::tick() {
    struct timespec tstart, tend;

    uint32_t token_bytes_obtained_from_fpga = 0;
    uint32_t token_bytes_sent_to_fpga = 0;

    #define DEBUG_NIC_PRINT
    //printf("AJG: entering tick");

    while (true) { // break when we don't have 5k tokens
        token_bytes_obtained_from_fpga = 0;
        token_bytes_sent_to_fpga = 0;

        uint32_t tokens_this_round = 0;

        uint32_t output_tokens_available = read(mmio_addrs->outgoing_count);
        uint32_t input_token_capacity = SIMLATENCY_BT - read(mmio_addrs->incoming_count);

        // we will read/write the min of tokens available / token input capacity
        tokens_this_round = std::min(output_tokens_available, input_token_capacity);
#ifdef DEBUG_NIC_PRINT
        niclog_printf("tokens this round: %d\n", tokens_this_round);
#endif

        if (tokens_this_round != SIMLATENCY_BT) {
#ifdef DEBUG_NIC_PRINT
            niclog_printf("FAIL: output available %d, input capacity: %d\n", output_tokens_available, input_token_capacity);
#endif
            return;
        }

        // read into read_buffer
#ifdef DEBUG_NIC_PRINT
        iter++;
        niclog_printf("read fpga iter %ld\n", iter);
#endif
        token_bytes_obtained_from_fpga = pull(
                0x0,
                pcis_read_bufs[currentround],
                BUFWIDTH * tokens_this_round);
#ifdef DEBUG_NIC_PRINT
        niclog_printf("send iter %ld\n", iter);
#endif

        pcis_read_bufs[currentround][BUFBYTES] = 1;

#ifdef TOKENVERIFY
        // the widget is designed to tag tokens with a 43 bit number,
        // incrementing for each sent token. verify that we are not losing
        // tokens over PCIS
        for (int i = 0; i < tokens_this_round; i++) {
            uint64_t TOKENLRV_AND_COUNT = *(((uint64_t*)pcis_read_bufs[currentround])+i*8);
            uint8_t LAST;
            for (int token_in_bigtoken = 0; token_in_bigtoken < 7; token_in_bigtoken++) {
                if (TOKENLRV_AND_COUNT & (1L << (43+token_in_bigtoken*3))) {
                    LAST = (TOKENLRV_AND_COUNT >> (45 + token_in_bigtoken*3)) & 0x1;
                    niclog_printf("sending to other node, valid data chunk: "
                                "%016lx, last %x, sendcycle: %016ld\n",
                                *((((uint64_t*)pcis_read_bufs[currentround])+i*8)+1+token_in_bigtoken),
                                LAST, timeelapsed_cycles + i*7 + token_in_bigtoken);
                }
            }

            //            *((uint64_t*)(pcis_read_buf + i*64)) |= 0x4924900000000000;
            uint32_t thistoken = *((uint32_t*)(pcis_read_bufs[currentround] + i*64));
            if (thistoken != next_token_from_fpga) {
                niclog_printf("FAIL! Token lost on FPGA interface.\n");
                exit(1);
            }
            next_token_from_fpga++;
        }
#endif
        if (token_bytes_obtained_from_fpga != tokens_this_round * BUFWIDTH) {
            printf("ERR MISMATCH! on reading tokens out. actually read %d bytes, wanted %d bytes.\n", token_bytes_obtained_from_fpga, BUFWIDTH * tokens_this_round);
            printf("errno: %s\n", strerror(errno));
            exit(1);
        }

#ifdef DEBUG_NIC_PRINT
        niclog_printf("recv iter %ld\n", iter);
#endif

#ifdef TOKENVERIFY
        timeelapsed_cycles += LINKLATENCY;
#endif

        if (!loopback) {
            volatile uint8_t * polladdr = (uint8_t*)(pcis_write_bufs[currentround] + BUFBYTES);
            while (*polladdr == 0) { ; }
        }
#ifdef DEBUG_NIC_PRINT
        niclog_printf("done recv iter %ld\n", iter);
#endif

#ifdef TOKENVERIFY
        // this does not do tokenverify - it's just printing tokens
        // there should not be tokenverify on this interface
        for (int i = 0; i < tokens_this_round; i++) {
            uint64_t TOKENLRV_AND_COUNT = *(((uint64_t*)pcis_write_bufs[currentround])+i*8);
            uint8_t LAST;
            for (int token_in_bigtoken = 0; token_in_bigtoken < 7; token_in_bigtoken++) {
                if (TOKENLRV_AND_COUNT & (1L << (43+token_in_bigtoken*3))) {
                    LAST = (TOKENLRV_AND_COUNT >> (45 + token_in_bigtoken*3)) & 0x1;
                    niclog_printf("from other node, valid data chunk: %016lx, "
                                "last %x, recvcycle: %016ld\n",
                                *((((uint64_t*)pcis_write_bufs[currentround])+i*8)+1+token_in_bigtoken),
                                LAST, timeelapsed_cycles + i*7 + token_in_bigtoken);
                }
            }
        }
#endif
        token_bytes_sent_to_fpga = push(
                0x0,
                pcis_write_bufs[currentround],
                BUFWIDTH * tokens_this_round);
        pcis_write_bufs[currentround][BUFBYTES] = 0;
        if (token_bytes_sent_to_fpga != tokens_this_round * BUFWIDTH) {
            printf("ERR MISMATCH! on writing tokens in. actually wrote in %d bytes, wanted %d bytes.\n", token_bytes_sent_to_fpga, BUFWIDTH * tokens_this_round);
            printf("errno: %s\n", strerror(errno));
            exit(1);
        }

        currentround = (currentround + 1) % 2;
        nextround = (nextround + 1) % 2;

    }
}

#endif // #ifdef SIMPLENICWIDGET_struct_guard

