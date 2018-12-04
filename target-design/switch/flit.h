#include <stdlib.h>

#define BROADCAST_ADJUSTED (0xffff)

/* ----------------------------------------------------
 * buffer flit operations
 *
 * ----------------------------------------------------
 */
// get a flit from recv_buf, given the token id
uint64_t get_flit(uint8_t * recv_buf, int tokenid) {
    int base = tokenid / TOKENS_PER_BIGTOKEN;
    int offset = tokenid % TOKENS_PER_BIGTOKEN;
    return *(((uint64_t*)recv_buf) + base * 8 + (offset+1));
}

// write a flit to send_buf
void write_flit(uint8_t * send_buf, int tokenid, uint64_t flit) {
    int base = tokenid / TOKENS_PER_BIGTOKEN;
    int offset = tokenid % TOKENS_PER_BIGTOKEN;
    *(((uint64_t*)send_buf) + base * 8 + (offset+1)) = flit;
}

// for a particular tokenid, determine if the flit is valid
int is_valid_flit(uint8_t * recv_buf, int tokenid) {
    int base = tokenid / TOKENS_PER_BIGTOKEN;
    int offset = tokenid % TOKENS_PER_BIGTOKEN;

    uint64_t lrv = ((uint64_t*)recv_buf)[base*8];
    int bitoffset = 43 + (offset * 3);
    return (lrv >> bitoffset) & 0x1;
}

int is_last_flit(uint8_t * recv_buf, int tokenid) {
    int base = tokenid / TOKENS_PER_BIGTOKEN;
    int offset = tokenid % TOKENS_PER_BIGTOKEN;

    uint64_t lrv = ((uint64_t*)recv_buf)[base*8];
    int bitoffset = 45 + (offset * 3);
    return (lrv >> bitoffset) & 0x1;
}

void write_valid_flit(uint8_t * send_buf, int tokenid) {
    int base = tokenid / TOKENS_PER_BIGTOKEN;
    int offset = tokenid % TOKENS_PER_BIGTOKEN;

    uint64_t * lrv = ((uint64_t*)send_buf) + base*8;
    int bitoffset = 43 + (offset * 3);
    *lrv |= (1L << bitoffset);
}

int write_last_flit(uint8_t * send_buf, int tokenid, int is_last) {
    int base = tokenid / TOKENS_PER_BIGTOKEN;
    int offset = tokenid % TOKENS_PER_BIGTOKEN;

    uint64_t * lrv = ((uint64_t*)send_buf) + base*8;
    int bitoffset = 45 + (offset * 3);
    *lrv |= (((uint64_t)is_last) << bitoffset);
}

/* get dest mac from flit, then get port from mac */
uint16_t get_port_from_flit(uint64_t flit, int current_port) {
    uint16_t is_multicast = (flit >> 16) & 0x1;
    uint16_t flit_low = (flit >> 48) & 0xFFFF; // indicates dest
    uint16_t sendport = (__builtin_bswap16(flit_low));

    if (is_multicast)
	return BROADCAST_ADJUSTED;

    sendport = sendport & 0xFFFF;
    //printf("mac: %04x\n", sendport);

    // At this point, we know the MAC address is not a broadcast address,
    // so we can just look up the port in the mac2port table
    sendport = mac2port[sendport];

    if (sendport == NUMDOWNLINKS) {
        // this has been mapped to "any uplink", so pick one
        int randval = rand() % NUMUPLINKS;
        sendport = randval + NUMDOWNLINKS;
//        printf("sending to random uplink.\n");
//        printf("port: %04x\n", sendport);
    }
    //printf("port: %04x\n", sendport);
    return sendport;
}
