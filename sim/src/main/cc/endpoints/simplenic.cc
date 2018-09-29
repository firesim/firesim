#include "simplenic.h"

#include <stdio.h>
#include <string.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include <sys/mman.h>

// IMPORTANT! LINKLATENCY CONFIG HAS MOVED TO simplenic.h

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

simplenic_t::simplenic_t(
        simif_t *sim, char *slotid,
        uint64_t mac_little_end, int netbw, int netburst, int linklatency,
        char *niclogfile): endpoint_t(sim)
{
#ifdef SIMPLENICWIDGET_0
    // store link latency:
    LINKLATENCY = linklatency;

    // store mac address
    mac_lendian = mac_little_end;

    // AJG: netbw should be lower than the max PCIE width (minus some) by the speed of the processor
    //      Note: For now this is MAX_BW since that is being used to created the limit below
    //assert( netbw <= (( PCIE_WIDTH - VAL_BITS ) * PROC_SPEED ) );
    assert( netbw <= MAX_BANDWIDTH );

    // AJG: Might have to make BUFWIDTH be the next smaller power of 2 so that you can simulate correctly (cannot have 509 as a actual datafield... right)
    BUFWIDTH = 128;//netbw / PROC_SPEED; //OG was 512/8
    TOKENS_PER_BIGTOKEN = 3;//PCIE_WIDTH / (BUFWIDTH + VAL_BITS); //OG was 7
    SIMLATENCY_BT = LINKLATENCY / TOKENS_PER_BIGTOKEN;
    BUFBYTES = SIMLATENCY_BT * BUFWIDTH;
    
    // AJG: Following is used to limit the amount of tokens the nic is given depending on the MAX_BW and the input BW
    //      Note: netburst is the amt of pkts put into a larger PCIE packet (ex. 64bit small flits can fit into 512bits 8 times so netburst can be up to 8)
    assert(netburst < 256);
    simplify_frac(netbw, MAX_BANDWIDTH, &rlimit_inc, &rlimit_period);
    rlimit_size = netburst;

    printf("using link latency: %d cycles\n", linklatency);
    printf("using netbw: %d\n", netbw);
    printf("using netburst: %d\n", netburst);
    printf("using SIMLATENCY_BT: %d\n", SIMLATENCY_BT);
    printf("using BUFBYTES: %d\n", BUFBYTES);
    printf("using rlimit_inc: %d\n", rlimit_inc);
    printf("using rlimit_period: %d\n", rlimit_period);

    this->niclog = NULL;
    if (niclogfile) {
        this->niclog = fopen(niclogfile, "w");
        if (!this->niclog) {
            fprintf(stderr, "Could not open NIC log file: %s\n", niclogfile);
            abort();
        }
    }

#ifndef SIMULATION_XSIM
    char name[100];
    int shmemfd;

    // Created shared memory regions for NIC
    for (int j = 0; j < 2; j++) {
        sprintf(name, "/port_nts%s_%d", slotid, j);
        printf("opening/creating shmem region %s\n", name);
        shmemfd = shm_open(name, O_RDWR | O_CREAT , S_IRWXU);
        ftruncate(shmemfd, BUFBYTES+EXTRABYTES);
        pcis_read_bufs[j] = (char*)mmap(NULL, BUFBYTES+EXTRABYTES, PROT_READ | PROT_WRITE, MAP_SHARED, shmemfd, 0);
        printf("AJG: created shmem region %s\n", name);
        sprintf(name, "/port_stn%s_%d", slotid, j);
        printf("opening/creating shmem region %s\n", name);
        shmemfd = shm_open(name, O_RDWR | O_CREAT , S_IRWXU);
        ftruncate(shmemfd, BUFBYTES+EXTRABYTES);
        pcis_write_bufs[j] = (char*)mmap(NULL, BUFBYTES+EXTRABYTES, PROT_READ | PROT_WRITE, MAP_SHARED, shmemfd, 0);
        printf("AJG: created shmem region %s\n", name);
    }
#endif // SIMULATION_XSIM
#endif // #ifdef SIMPLENICWIDGET_0
}

simplenic_t::~simplenic_t() {
    if (this->niclog)
        fclose(this->niclog);
}

#define ceil_div(n, d) (((n) - 1) / (d) + 1)

void simplenic_t::init() {
    printf( "AJG: entering init" );
#ifdef SIMPLENICWIDGET_0
    write(SIMPLENICWIDGET_0(macaddr_upper), (mac_lendian >> 32) & 0xFFFF);
    write(SIMPLENICWIDGET_0(macaddr_lower), mac_lendian & 0xFFFFFFFF);
    // AJG: At this point this is written in memory and sent to the actual NIC
    write(SIMPLENICWIDGET_0(rlimit_settings),
            (rlimit_inc << 16) | ((rlimit_period - 1) << 8) | rlimit_size);

    //AJG: Should I care about the BUFWIDTH?
#ifndef SIMULATION_XSIM
    uint32_t output_tokens_available = read(SIMPLENICWIDGET_0(outgoing_count));
    uint32_t input_token_capacity = SIMLATENCY_BT - read(SIMPLENICWIDGET_0(incoming_count));
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
#endif
    return;
#endif // ifdef SIMPLENICWIDGET_0
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
#ifdef SIMPLENICWIDGET_0
#ifndef SIMULATION_XSIM
    struct timespec tstart, tend;

    uint32_t token_bytes_obtained_from_fpga = 0;
    uint32_t token_bytes_sent_to_fpga = 0;

    #define DEBUG_NIC_PRINT
    printf("AJG: entering tick");

    while (true) { // break when we don't have 5k tokens
        token_bytes_obtained_from_fpga = 0;
        token_bytes_sent_to_fpga = 0;

        uint32_t tokens_this_round = 0;

        uint32_t output_tokens_available = read(SIMPLENICWIDGET_0(outgoing_count));
        uint32_t input_token_capacity = SIMLATENCY_BT - read(SIMPLENICWIDGET_0(incoming_count));

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

        volatile uint8_t * polladdr = (uint8_t*)(pcis_write_bufs[currentround] + BUFBYTES);
        while (*polladdr == 0) { ; }
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
#endif
#endif // ifdef SIMPLENICWIDGET_0
}
