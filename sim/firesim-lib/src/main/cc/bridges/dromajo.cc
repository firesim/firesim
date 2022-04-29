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
#define QUEUE_DEPTH 6144
// Size of PCI intf. in bytes
#define PCIE_SZ_B 64
// Create bitmask macro
#define BIT_MASK(__TYPE__, __ONE_COUNT__) \
        ((__TYPE__) (-((__ONE_COUNT__) != 0))) \
        & (((__TYPE__) -1) >> ((sizeof(__TYPE__) * CHAR_BIT) - (__ONE_COUNT__)))

//#define DEBUG

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
    int num_traces,
    DROMAJOBRIDGEMODULE_struct * mmio_addrs,
    long dma_addr) :
        bridge_driver_t(sim),
        stream_count_address(stream_count_address),
        stream_full_address(stream_full_address)
{
    // setup max constants given from the RTL
    this->_num_traces = num_traces;

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
    this->_trace_idx = 0;
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

    printf("[INFO] Dromajo: Attached Dromajo to %d instruction traces\n", this->_num_traces);

    // setup arguments
    char local_argc = 21;
    char* local_argv[MAX_ARGS] = {
        "./dromajo",
        "--compact_bootrom",
        "--custom_extension",
        "--clear_ids",
        "--reset_vector",
        DROMAJO_RESET_VECTOR,
        "--bootrom",
        (char*)this->dromajo_bootrom.c_str(),
        "--mmio_range",
        DROMAJO_MMIO_START ":" DROMAJO_MMIO_END,
        "--plic",
        DROMAJO_PLIC_BASE ":" DROMAJO_PLIC_SIZE,
        "--clint",
        DROMAJO_CLINT_BASE ":" DROMAJO_CLINT_SIZE,
        "--memory_size",
        DROMAJO_MEM_SIZE,
        "--save",
        "dromajo_snap",
        "--dtb",
        (char*)this->dromajo_dtb.c_str(),
        (char*)this->dromajo_bin.c_str()
    };

    if (MAX_ARGS < local_argc) {
        printf("[DRJ_ERR] Too many arguments\n");
        exit(1);
    }

    printf("[INFO] Dromajo command: \n");
    for(int i = 0; i < local_argc; ++i) {
        printf("%s ", local_argv[i]);
    }
    printf("\n");

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
    // this crazy to extract the right value then sign extend within the size
    uint64_t pc = ((int64_t)(*((int64_t*)(buf + this->_iaddr_offset)) & BIT_MASK(uint64_t, this->_iaddr_width*8)) << (sizeof(uint64_t) - this->_iaddr_width)*8) >> (sizeof(uint64_t) - this->_iaddr_width)*8;
    uint32_t insn = ((int32_t)(*((int32_t*)(buf + this->_insn_offset)) & BIT_MASK(uint32_t, this->_insn_width*8)) << (sizeof(uint32_t) - this->_insn_width)*8) >> (sizeof(uint32_t) - this->_insn_width)*8;
    uint64_t wdata = ((int64_t)(*((int64_t*)(buf + this->_wdata_offset)) & BIT_MASK(uint64_t, this->_wdata_width*8)) << (sizeof(uint64_t) - this->_wdata_width)*8) >> (sizeof(uint64_t) - this->_wdata_width)*8;
    uint64_t mstatus = 0; // default not checked
    bool check = true; // default check all
    bool interrupt = buf[this->_interrupt_offset];
    bool exception = buf[this->_exception_offset];
    int64_t cause = (*((uint64_t*)(buf + this->_cause_offset)) & BIT_MASK(uint64_t, this->_cause_width*8)) | ((uint64_t)interrupt << 63);

    if (valid) {
#ifdef DEBUG
        if (interrupt || exception)
            fprintf(stderr, "[DEBUG] INT/EXCEP raised (on valid): cause = %lx\n", cause);
#endif
        return dromajo_cosim_step(this->dromajo_state, hartid, pc, insn, wdata, mstatus, check);
    }

    if ((interrupt || exception) && !(this->saw_int_excp)) {
#ifdef DEBUG
        fprintf(stderr, "[DEBUG] INT/EXCEP raised (normal): cause = %lx\n", cause);
#endif
        dromajo_cosim_raise_trap(this->dromajo_state, hartid, cause);
        this->saw_int_excp = true;
    }

    return 0;
}

/**
 * Read queue and co-simulate
 */
void dromajo_t::process_tokens(int num_beats) {
    // TODO: as opt can mmap file and just load directly into it.
    alignas(4096) uint8_t OUTBUF[QUEUE_DEPTH * sizeof(uint64_t) * 8];
    pull(this->_dma_addr, (char*)OUTBUF, num_beats * sizeof(uint64_t) * 8);

    // skip if co-sim not enabled
    if (!this->dromajo_cosim) return;

    for (uint32_t offset = 0; offset < num_beats*sizeof(uint64_t)*8; offset += PCIE_SZ_B/2) {
        // invoke dromajo (requires that buffer is aligned properly)
        int rval = this->invoke_dromajo(OUTBUF + offset);
        if (rval) {
            dromajo_failed = true;
            dromajo_exit_code = rval;
            printf("[ERROR] Dromajo: Errored during simulation with %d\n", rval);

#ifdef DEBUG
            fprintf(stderr, "C[%d] off(%d) token(", this->_trace_idx, offset / (PCIE_SZ_B/2));

            for (int32_t i = PCIE_SZ_B - 1; i >= 0; --i) {
                fprintf(stderr, "%02x", (OUTBUF + offset)[i]);
                if (i == PCIE_SZ_B/2) fprintf(stderr, " ");
            }
            fprintf(stderr, ")\n");

            fprintf(stderr, "get_next_token token(");
            uint32_t next_off = offset += PCIE_SZ_B;

            for (int32_t i = PCIE_SZ_B - 1; i >= 0; --i) {
                fprintf(stderr, "%02x", (OUTBUF + next_off)[i]);
                if (i == PCIE_SZ_B/2) fprintf(stderr, " ");
            }
            fprintf(stderr, ")\n");
#endif

            return;
        }

        // move to next inst. trace
        this->_trace_idx = (this->_trace_idx + 1) % this->_num_traces;

        // if int/excp was found in this set of commit traces... reset on next set of commit traces
        if (this->_trace_idx == 0){
            this->saw_int_excp = false;
        }

        // add an extra PCIE_SZ_B if there is an odd amount of traces
        if (this->_trace_idx == 0 && (this->_num_traces % 2 == 1)) {
#ifdef DEBUG
            fprintf(stderr, "off(%d + 1) = %d\n", offset / (PCIE_SZ_B/2), (offset + PCIE_SZ_B/2) / (PCIE_SZ_B/2));
#endif
            offset += PCIE_SZ_B/2;
        }
    }
}

/**
 * Move forward the simulation
 */
void dromajo_t::tick() {
    uint64_t trace_queue_full = read(stream_full_address);

    if (trace_queue_full) this->process_tokens(QUEUE_DEPTH);
}

/**
 * Find out how many beats are left in the queue (waits for things to stablize)
 */
int dromajo_t::beats_available_stable() {
  size_t prev_beats_available = 0;
  size_t beats_available = read(stream_count_address);
  while (beats_available > prev_beats_available) {
    prev_beats_available = beats_available;
    beats_available = read(stream_count_address);
  }
  return beats_available;
}

/**
 * Pull in any remaining tokens and use them (if the simulation hasn't already failed)
 */
void dromajo_t::flush() {
    // only flush if there wasn't a failure before
    if (!dromajo_failed) {
        size_t beats_available = beats_available_stable();
        this->process_tokens(beats_available);
    }
}

#endif // DROMAJOBRIDGEMODULE_struct_guard
