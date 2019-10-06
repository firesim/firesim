#ifndef __SYNTHESIZED_PRINTS_H
#define __SYNTHESIZED_PRINTS_H

#ifdef PRINTBRIDGEMODULE_struct_guard

#include <vector>
#include <iostream>
#include <fstream>
#include <gmp.h>

#include "bridge_driver.h"

struct print_vars_t {
  std::vector<mpz_t*> data;
  ~print_vars_t() {
    for (auto& e: data) {
      mpz_clear(*e);
      free(e);
    }
  }
};

class synthesized_prints_t: public bridge_driver_t
{

    public:
        synthesized_prints_t(simif_t* sim,
                             std::vector<std::string> &args,
                             PRINTBRIDGEMODULE_struct * mmio_addrs,
                             unsigned int print_count,
                             unsigned int token_bytes,
                             unsigned int idle_cycles_mask,
                             const unsigned int* print_offsets,
                             const char* const*  format_strings,
                             const unsigned int* argument_counts,
                             const unsigned int* argument_widths,
                             unsigned int dma_address);
        ~synthesized_prints_t();
        virtual void init();
        virtual void tick();
        virtual bool terminate() { return false; };
        virtual int exit_code() { return 0; };
        void flush();
        void finish() { flush(); };
    private:
        PRINTBRIDGEMODULE_struct * mmio_addrs;
        const unsigned int print_count;
        const unsigned int  token_bytes;
        const unsigned int  idle_cycles_mask;
        const unsigned int* print_offsets;
        const char* const*  format_strings;
        const unsigned int* argument_counts;
        const unsigned int* argument_widths;
        const unsigned int dma_address;

        // DMA batching parameters
        const size_t beat_bytes  = DMA_DATA_BITS / 8;
        // The number of DMA beats to pull off the FPGA on each invocation of tick()
        // This will be set based on the ratio of token_size : desired_batch_beats
        size_t batch_beats;
        // This will be modified to be a multiple of the token size
        const size_t desired_batch_beats = 3072;

        // Used to define the boundaries in the batch buffer at which we'll
        // initalize GMP types
        using gmp_align_t = uint64_t;
        const size_t gmp_align_bits = sizeof(gmp_align_t) * 8;


        // +arg driven members
        std::ofstream printfile;   // Used only if the +print-file arg is provided
        std::string default_filename = "synthesized-prints.out";

        std::ostream* printstream; // Is set to std::cerr otherwise
        uint64_t start_cycle, end_cycle; // Bounds between which prints will be emitted
        uint64_t current_cycle = 0;
        bool human_readable = true;
        bool print_cycle_prefix = true;

        std::vector<std::vector<size_t>> widths;
        std::vector<size_t> sizes;
        std::vector<print_vars_t*> masks;

        std::vector<size_t> aligned_offsets; // Aligned to gmp_align_t
        std::vector<size_t> bit_offset;

        bool current_print_enabled(gmp_align_t* buf, size_t offset);
        void process_tokens(size_t beats);
        void show_prints(char * buf);
        void print_format(const char* fmt, print_vars_t* vars, print_vars_t* masks);
        // Returns the number of beats available, once two successive reads return the same value
        int beats_avaliable_stable();
};

#endif // PRINTBRIDGEMODULE_struct_guard

#endif //__SYNTHESIZED_PRINTS_H
