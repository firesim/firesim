//See LICENSE for license details
#ifdef DROMAJOBRIDGEMODULE_struct_guard

#include "dromajo.h"

#include <assert.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <limits.h>

#include "dromajo_params.h"

// The maximum number of beats available in the FPGA-side FIFO
#define QUEUE_DEPTH 64
// Size of PCI intf. in bytes
#define PCIE_SZ_B 64
// Create bitmask macro
#define BIT_MASK(__TYPE__, __ONE_COUNT__) \
        ((__TYPE__) (-((__ONE_COUNT__) != 0))) \
        & (((__TYPE__) -1) >> ((sizeof(__TYPE__) * CHAR_BIT) - (__ONE_COUNT__)))

/**
 * Constructor for Dromajo
 */
dromajo_t::dromajo_t(
    simif_t *sim,
    std::vector<std::string> &args,
    int iaddr_width,
    int insn_width,
    int wdata_width,
    int cause_width,
    int tval_width,
    int num_streams,
    DROMAJOBRIDGEMODULE_struct * mmio_addrs,
    long dma_addr) : bridge_driver_t(sim)
{
    // setup max constants given from the RTL
    this->_num_streams = num_streams;

    this->_valid_width = 1;
    this->_iaddr_width = iaddr_width/8;
    this->_insn_width = insn_width/8;
    this->_wdata_width = wdata_width/8;
    this->_priv_width = 1;
    this->_exception_width = 1;
    this->_interrupt_width = 1;
    this->_cause_width = cause_width/8;
    this->_tval_width = tval_width/8;
    this->_valid_offset = 0;
    this->_iaddr_offset = this->_valid_offset + this->_valid_width;;
    this->_insn_offset = this->_iaddr_offset + this->_iaddr_width;
    this->_wdata_offset = this->_insn_offset + this->_insn_width;
    this->_priv_offset = this->_wdata_offset + this->_wdata_width;
    this->_exception_offset = this->_priv_offset + this->_priv_width;
    this->_interrupt_offset = this->_exception_offset + this->_exception_width;
    this->_cause_offset = this->_interrupt_offset + this->_interrupt_width;
    this->_tval_offset = this->_cause_offset + this->_cause_width;

    // setup misc. state variables
    this->_mmio_addrs = mmio_addrs;
    this->_dma_addr = dma_addr;
    this->_stream_idx = 0;
    this->dromajo_failed = false;
    this->dromajo_exit_code = 0;
    this->dromajo_state = NULL;
    this->dromajo_cosim = true; // by default enable cosim
    this->saw_int_excp = false; // used to not trigger interrupts multiple times

    // setup dromajo variables
    std::string dromajo_dtb_arg = std::string("+drj_dtb=");
    std::string dromajo_bin_arg = std::string("+drj_bin=");
    std::string dromajo_rom_arg = std::string("+drj_rom=");
    bool dtb_found = false;
    bool bin_found = false;
    bool bootrom_found = false;
    this->dromajo_dtb = "";
    this->dromajo_bootrom = "";
    this->dromajo_bin = "";
    const char *d_rom = NULL;
    const char *d_dtb = NULL;
    const char *d_bin = NULL;

    for (auto &arg: args) {
        if (arg.find(dromajo_dtb_arg) == 0) {
            d_dtb = const_cast<char*>(arg.c_str()) + dromajo_dtb_arg.length();
            this->dromajo_dtb = std::string(d_dtb);
            dtb_found = true;
        }
        if (arg.find(dromajo_rom_arg) == 0) {
            d_rom = const_cast<char*>(arg.c_str()) + dromajo_rom_arg.length();
            this->dromajo_bootrom = std::string(d_rom);
            bootrom_found = true;
        }
        if (arg.find(dromajo_bin_arg) == 0) {
            d_bin = const_cast<char*>(arg.c_str()) + dromajo_bin_arg.length();
            this->dromajo_bin = std::string(d_bin);
            bin_found = true;
        }
    }

    if (!dtb_found || !bin_found || !bootrom_found) {
        printf("[WARNING] Missing Dromajo input file(s) (make sure you have a dtb, bin, and bootrom passed in)\n");
        printf("[WARNING] Disabling Dromajo Bridge\n");
        this->dromajo_cosim = false;
    }
}

/**
 * Destructor for Dromajo
 */
dromajo_t::~dromajo_t() {
    free(this->_mmio_addrs);

    if (this->dromajo_state != NULL)
        dromajo_cosim_fini(this->dromajo_state);
}

#define MAX_ARGS 24
#define MAX_STR_LEN 24

/**
 * Setup simulation and initialize Dromajo cosimulation
 */
void dromajo_t::init() {
    // skip if co-sim not enabled
    if (!this->dromajo_cosim) return;

    printf("[INFO] Dromajo: Attached Dromajo to %d instruction streams\n", this->_num_streams);

    char *local_argv[MAX_ARGS];
    char local_argc = 0;
    char mmio_range[MAX_STR_LEN] = "";
    char plic_params[MAX_STR_LEN] = "";
    char clint_params[MAX_STR_LEN] = "";

    local_argv[local_argc] = (char*)"./dromajo";
    local_argc += 1;
    local_argv[local_argc] = (char*)"--compact_bootrom";
    local_argc += 1;
    local_argv[local_argc] = (char*)"--custom_extension";
    local_argc += 1;
    local_argv[local_argc] = (char*)"--reset_vector";
    local_argc += 1;
    local_argv[local_argc] = (char*)DROMAJO_RESET_VECTOR;
    local_argc += 1;
    local_argv[local_argc] = (char*)"--bootrom";
    local_argc += 1;
    local_argv[local_argc] = (char*)this->dromajo_bootrom.c_str();
    local_argc += 1;
    local_argv[local_argc] = (char*)"--mmio_range";
    local_argc += 1;
    strcat(mmio_range, (char*)DROMAJO_MMIO_START);
    strcat(mmio_range, ":");
    strcat(mmio_range, (char*)DROMAJO_MMIO_END);
    local_argv[local_argc] = (char*)mmio_range;
    local_argc += 1;
    local_argv[local_argc] = (char*)"--plic";
    local_argc += 1;
    strcat(plic_params, (char*)DROMAJO_PLIC_BASE);
    strcat(plic_params, ":");
    strcat(plic_params, (char*)DROMAJO_PLIC_SIZE);
    local_argv[local_argc] = (char*)plic_params;
    local_argc += 1;
    local_argv[local_argc] = (char*)"--clint";
    local_argc += 1;
    strcat(clint_params, (char*)DROMAJO_CLINT_BASE);
    strcat(clint_params, ":");
    strcat(clint_params, (char*)DROMAJO_CLINT_SIZE);
    local_argv[local_argc] = (char*)clint_params;
    local_argc += 1;
    local_argv[local_argc] = (char*)"--memory_size";
    local_argc += 1;
    local_argv[local_argc] = (char*)DROMAJO_MEM_SIZE;
    local_argc += 1;
    local_argv[local_argc] = (char*)"--save";
    local_argc += 1;
    local_argv[local_argc] = (char*)"dromajo_snap";
    local_argc += 1;
    local_argv[local_argc] = (char*)"--dtb";
    local_argc += 1;
    local_argv[local_argc] = (char*)this->dromajo_dtb.c_str();
    local_argc += 1;

    local_argv[local_argc] = (char*)this->dromajo_bin.c_str();
    local_argc += 1;

    this->dromajo_state = dromajo_cosim_init(local_argc, local_argv);
    if (this->dromajo_state == NULL) {
        printf("[ERROR] Error setting up Dromajo\n");
        exit(1);
    }
}

/**
 * Call Dromajo co-sim functions with an aligned buffer.
 * This returns the return code of the co-sim functions.
 */
int dromajo_t::invoke_dromajo(uint8_t* buf) {
    bool valid = buf[0];
    int hartid = 0; // only works for single core
    uint64_t pc = *((uint64_t*)(buf + this->_iaddr_offset)) & BIT_MASK(uint64_t, this->_iaddr_width*8);
    uint32_t insn = *((uint32_t*)(buf + this->_insn_offset)) & BIT_MASK(uint32_t, this->_insn_width*8);
    uint64_t wdata = *((uint64_t*)(buf + this->_wdata_offset)) & BIT_MASK(uint64_t, this->_wdata_width*8);
    uint64_t mstatus = 0; // default not checked
    bool check = true; // default check all
    bool interrupt = buf[this->_interrupt_offset];
    bool exception = buf[this->_exception_offset];
    int64_t cause = (*((uint64_t*)(buf + this->_cause_offset)) & BIT_MASK(uint64_t, this->_cause_width*8)) | ((uint64_t)interrupt << 63);

    if (valid) {
        return dromajo_cosim_step(this->dromajo_state, hartid, pc, insn, wdata, mstatus, check);
    }

    if ((interrupt || exception) && !(this->saw_int_excp)) {
        dromajo_cosim_raise_trap(this->dromajo_state, hartid, cause);
        this->saw_int_excp = true;
    }

    return 0;
}

/**
 * Move forward the simulation
 */
void dromajo_t::tick() {
    // skip if co-sim not enabled
    if (!this->dromajo_cosim) return;

    uint64_t trace_queue_full = read(this->_mmio_addrs->trace_queue_full);

    alignas(4096) uint8_t OUTBUF[QUEUE_DEPTH * sizeof(uint64_t) * 8];

    if (trace_queue_full) {
        // TODO: as opt can mmap file and just load directly into it.
        pull(this->_dma_addr, (char*)OUTBUF, QUEUE_DEPTH * sizeof(uint64_t) * 8);

        for (uint32_t offset = 0; offset < QUEUE_DEPTH*sizeof(uint64_t)*8; offset += PCIE_SZ_B/2) {
            // invoke dromajo
            //fprintf(stderr, "C[%d] off(%d) token(", this->_stream_idx, offset / (PCIE_SZ_B/2));

            //for (int32_t i = PCIE_SZ_B - 1; i >= 0; --i) {
            //    fprintf(stderr, "%02x", (OUTBUF + offset)[i]);
            //    if (i == PCIE_SZ_B/2) fprintf(stderr, " ");
            //}
            //fprintf(stderr, ")\n");

            int rval = this->invoke_dromajo(OUTBUF + offset);
            if (rval) {
                dromajo_failed = true;
                dromajo_exit_code = rval;
                return;
            }

            // move to next i stream
            this->_stream_idx = (this->_stream_idx + 1) % this->_num_streams;

            // if int/excp was found in this set of commit traces... reset on next set of commit traces
            if (this->_stream_idx == 0){
                this->saw_int_excp = false;
            }

            // add an extra PCIE_SZ_B if there is an odd amount of streams
            if (this->_stream_idx == 0 && (this->_num_streams % 2 == 1)) {
                //fprintf(stderr, "off(%d + 1) = %d\n", offset / (PCIE_SZ_B/2), (offset + PCIE_SZ_B/2) / (PCIE_SZ_B/2));
                offset += PCIE_SZ_B/2;
            }
        }
    }
}

/**
 * Find out how many beats are left in the queue (waits for things to stablize)
 */
int dromajo_t::beats_available_stable() {
  size_t prev_beats_available = 0;
  size_t beats_available = read(this->_mmio_addrs->outgoing_count);
  while (beats_available > prev_beats_available) {
    prev_beats_available = beats_available;
    beats_available = read(this->_mmio_addrs->outgoing_count);
  }
  return beats_available;
}

/**
 * Pull in any remaining tokens and use them (if the simulation hasn't already failed)
 */
void dromajo_t::flush() {
    // skip if co-sim not enabled
    if (!this->dromajo_cosim) return;

    // only flush if there wasn't a failure before
    if (!dromajo_failed) {
        alignas(4096) uint8_t OUTBUF[QUEUE_DEPTH * sizeof(uint64_t) * 8];

        size_t beats_available = beats_available_stable();

        // TODO. as opt can mmap file and just load directly into it.
        pull(this->_dma_addr, (char*)OUTBUF, beats_available * sizeof(uint64_t) * 8);

        for (uint32_t offset = 0; offset < beats_available*sizeof(uint64_t)*8; offset += PCIE_SZ_B/2) {
            // invoke dromajo
            int rval = this->invoke_dromajo(OUTBUF + offset);
            if (rval) {
                dromajo_failed = true;
                dromajo_exit_code = rval;
                printf("[ERR] Dromajo: Errored when flushing tokens with %d\n", rval);
                return;
            }

            // move to next i stream
            this->_stream_idx = (this->_stream_idx + 1) % this->_num_streams;

            // if int/excp was found in this set of commit traces... reset on next set of commit traces
            if (this->_stream_idx == 0){
                this->saw_int_excp = false;
            }

            // add an extra PCIE_SZ_B if there is an odd amount of streams
            if (this->_stream_idx == 0 && (this->_num_streams % 2 == 1)) {
                offset += PCIE_SZ_B/2;
            }
        }
    }
}

#endif // DROMAJOBRIDGEMODULE_struct_guard
