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
#define TOKENS_PER_BIGTOKEN 7

#define NUM_SUBNODES 4
#define SUBNODE0 0x00000000
#define SUBNODE1 0x10000000
#define SUBNODE2 0x20000000
#define SUBNODE3 0x30000000

int subnodeaddrs[NUM_SUBNODES] = { SUBNODE0, SUBNODE1, SUBNODE2, SUBNODE3 };

#define SIMLATENCY_BT (LINKLATENCY/TOKENS_PER_BIGTOKEN)

#define BUFWIDTH (512/8)
#define SINGLEBUFBYTES (SIMLATENCY_BT*BUFWIDTH)
#define ALLBUFBYTES (SINGLEBUFBYTES*NUM_SUBNODES)
#define EXTRABYTES 1


int currentround = 0;
int nextround = 1;


void simplenic_t::checked_push(int subnodeno) {
    uint32_t token_bytes_obtained_from_fpga = push(
            subnodeaddrs[subnodeno],
            pcis_write_bufs[subnodeno][currentround],
            SINGLEBUFBYTES);

#ifdef DEBUG_NIC_PRINT
      printf("push wrote %d to subnode %d\n", token_bytes_obtained_from_fpga, subnodeno);
#endif
    if (token_bytes_obtained_from_fpga != SINGLEBUFBYTES) { 
        printf("FAIL, incorrect push. subnode %d, token_bytes_obtained_from_fpga=%d\n", subnodeno, token_bytes_obtained_from_fpga);
        exit(1);
    }
}

void simplenic_t::checked_pull(int subnodeno) {
    uint32_t token_bytes_sent_to_fpga = pull(
            subnodeaddrs[subnodeno],
            pcis_read_bufs[subnodeno][currentround],
            SINGLEBUFBYTES);

#ifdef DEBUG_NIC_PRINT
      printf("pull read %d to subnode %d\n", token_bytes_sent_to_fpga, subnodeno);
#endif
    if (token_bytes_sent_to_fpga != SINGLEBUFBYTES) { 
        printf("FAIL, incorrect pull. subnode %d, token_bytes_sent_to_fpga=%d\n", subnodeno, token_bytes_sent_to_fpga);
        exit(1);
    }
}


//#define checkprint(x, y, message) \
//    printf("%s: %d\n", message, x); \
//    if (x != y) { \
//        return; \
//    }

#define checkprint(x, y, message) \
    if (x != y) { \
        return; \
    }


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

simplenic_t::simplenic_t(simif_t* sim, AddressMap addr_map, char * slotid, char subslotid[4], uint64_t mac_little_end[4], int netbw, int netburst, int linklatency): endpoint_t(sim, addr_map)
{
    // store link latency:
    LINKLATENCY = linklatency;
    // store mac address
    for (int i=0; i<4; i++)
    {
      mac_lendian[i] = mac_little_end[i];
    }

    assert(netbw <= MAX_BANDWIDTH);
    assert(netburst < 256);
    simplify_frac(netbw, MAX_BANDWIDTH, &rlimit_inc, &rlimit_period);
    rlimit_size = netburst;

    printf("using link latency: %d cycles\n", linklatency);
    printf("using netbw: %d\n", netbw);
    printf("using netburst: %d\n", netburst);

#ifndef SIMULATION_XSIM
    char name[100];
    int shmemfd;

    for (int subnode=0; subnode<NUM_SUBNODES; subnode++)
    {
      for (int j = 0; j < 2; j++) {
          sprintf(name, "/port_nts%d_%d", (atoi(slotid)*NUM_SUBNODES)+subnode, j);
          printf("opening/creating shmem region %s\n", name);
          shmemfd = shm_open(name, O_RDWR | O_CREAT , S_IRWXU);
          ftruncate(shmemfd, SINGLEBUFBYTES+EXTRABYTES);
          pcis_read_bufs[subnode][j] = (char*)mmap(NULL, SINGLEBUFBYTES+EXTRABYTES, PROT_READ | PROT_WRITE, MAP_SHARED, shmemfd, 0);
          sprintf(name, "/port_stn%d_%d", (atoi(slotid)*NUM_SUBNODES)+subnode, j);
          printf("opening/creating shmem region %s\n", name);
          shmemfd = shm_open(name, O_RDWR | O_CREAT , S_IRWXU);
          ftruncate(shmemfd, SINGLEBUFBYTES+EXTRABYTES);
          pcis_write_bufs[subnode][j] = (char*)mmap(NULL, SINGLEBUFBYTES+EXTRABYTES, PROT_READ | PROT_WRITE, MAP_SHARED, shmemfd, 0);
      }
   }
#endif // SIMULATION_XSIM
}

simplenic_t::~simplenic_t() {
}


#define ceil_div(n, d) (((n) - 1) / (d) + 1)

void simplenic_t::init() {
#ifdef SIMPLENICWIDGET_0
    write_m(SIMPLENICWIDGET_0(macaddr_upper), (mac_lendian[0] >> 32) & 0xFFFF);
    write_m(SIMPLENICWIDGET_0(macaddr_lower), mac_lendian[0] & 0xFFFFFFFF);
    write_m(SIMPLENICWIDGET_0(rlimit_settings),
            (rlimit_inc << 16) | ((rlimit_period - 1) << 8) | rlimit_size);
    write_m(SIMPLENICWIDGET_1(macaddr_upper), (mac_lendian[1] >> 32) & 0xFFFF);
    write_m(SIMPLENICWIDGET_1(macaddr_lower), mac_lendian[1] & 0xFFFFFFFF);
    write_m(SIMPLENICWIDGET_1(rlimit_settings),
        (rlimit_inc << 16) | ((rlimit_period - 1) << 8) | rlimit_size);
    write_m(SIMPLENICWIDGET_2(macaddr_upper), (mac_lendian[2] >> 32) & 0xFFFF);
    write_m(SIMPLENICWIDGET_2(macaddr_lower), mac_lendian[2] & 0xFFFFFFFF);
    write_m(SIMPLENICWIDGET_2(rlimit_settings),
        (rlimit_inc << 16) | ((rlimit_period - 1) << 8) | rlimit_size);
    write_m(SIMPLENICWIDGET_3(macaddr_upper), (mac_lendian[3] >> 32) & 0xFFFF);
    write_m(SIMPLENICWIDGET_3(macaddr_lower), mac_lendian[3] & 0xFFFFFFFF);
    write_m(SIMPLENICWIDGET_3(rlimit_settings),
        (rlimit_inc << 16) | ((rlimit_period - 1) << 8) | rlimit_size);

#ifndef SIMULATION_XSIM

    checkprint(read_m(SIMPLENICWIDGET_0(incoming_count)), 0, "incoming tokens 0");
    checkprint(read_m(SIMPLENICWIDGET_1(incoming_count)), 0, "incoming tokens 1");
    checkprint(read_m(SIMPLENICWIDGET_2(incoming_count)), 0, "incoming tokens 2");
    checkprint(read_m(SIMPLENICWIDGET_3(incoming_count)), 0, "incoming tokens 3");

    checkprint(read_m(SIMPLENICWIDGET_0(outgoing_count)), 0, "outgoing tokens 0");
    checkprint(read_m(SIMPLENICWIDGET_1(outgoing_count)), 0, "outgoing tokens 1");
    checkprint(read_m(SIMPLENICWIDGET_2(outgoing_count)), 0, "outgoing tokens 2");
    checkprint(read_m(SIMPLENICWIDGET_3(outgoing_count)), 0, "outgoing tokens 3");

    uint32_t token_bytes_produced = 0;
    for (int subnode = 0; subnode < NUM_SUBNODES; subnode++) {
        checked_push(subnode);
    }

#endif
    return;
#endif
}


bool simplenic_t::done() {
#ifdef SIMPLENICWIDGET_0
  return (read_m(SIMPLENICWIDGET_0(done)) && read_m(SIMPLENICWIDGET_1(done)) && read_m(SIMPLENICWIDGET_2(done)) && read_m(SIMPLENICWIDGET_3(done)));
#else
    return true;
#endif
}

//#define TOKENVERIFY

// checking for token loss
uint32_t next_token_from_fpga = 0x0;
uint32_t next_token_from_socket = 0x0;

uint64_t iter = 0;

#ifdef TOKENVERIFY
uint64_t timeelapsed_cycles = 0;
#endif

void simplenic_t::tick() {
#ifdef SIMPLENICWIDGET_0
#ifndef SIMULATION_XSIM
    struct timespec tstart, tend;

    //#define DEBUG_NIC_PRINT
    while (true) {
        checkprint((read_m(SIMPLENICWIDGET_0(outgoing_count))), SIMLATENCY_BT, "outgoing count 0");
        checkprint((read_m(SIMPLENICWIDGET_1(outgoing_count))), SIMLATENCY_BT, "outgoing count 1");
        checkprint((read_m(SIMPLENICWIDGET_2(outgoing_count))), SIMLATENCY_BT, "outgoing count 2");
        checkprint((read_m(SIMPLENICWIDGET_3(outgoing_count))), SIMLATENCY_BT, "outgoing count 3");

        checkprint((read_m(SIMPLENICWIDGET_0(incoming_count))), 0, "incoming count 0");
        checkprint((read_m(SIMPLENICWIDGET_1(incoming_count))), 0, "incoming count 1");
        checkprint((read_m(SIMPLENICWIDGET_2(incoming_count))), 0, "incoming count 2");
        checkprint((read_m(SIMPLENICWIDGET_3(incoming_count))), 0, "incoming count 3");

        for (int subnode = 0; subnode < NUM_SUBNODES; subnode++) {
            checked_pull(subnode);
            pcis_read_bufs[subnode][currentround][SINGLEBUFBYTES] = 1;

        }

        for (int subnode = 0; subnode < NUM_SUBNODES; subnode++) {
            volatile uint8_t * polladdr = (uint8_t*)(pcis_write_bufs[subnode][currentround] + SINGLEBUFBYTES);
            while (*polladdr == 0) { ; }

            checked_push(subnode);
            pcis_write_bufs[subnode][currentround][SINGLEBUFBYTES] = 0;
        }

        currentround = (currentround + 1) % 2;
        nextround = (nextround + 1) % 2;

    }
#endif
#endif // ifdef SIMPLENICWIDGET_0
}

