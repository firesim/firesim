#ifndef __SYNTHESIZED_PRINTS_H
#define __SYNTHESIZED_PRINTS_H

#ifdef PRINTWIDGET_struct_guard

#include <gmp.h>
#include <vector>

#include "endpoint.h"

struct print_vars_t {
  std::vector<mpz_t*> data;
  ~print_vars_t() {
    for (auto& e: data) {
      //mpz_clear(*e);
      //free(e);
    }
  }
};

class synthesized_prints_t: public endpoint_t
{

    public:
        synthesized_prints_t(simif_t* sim,
                              PRINTWIDGET_struct * mmio_addrs,
                              unsigned int print_count,
                              const unsigned int* print_offsets,
                              const char* const*  format_strings,
                              const unsigned int* argument_counts,
                              const unsigned int* argument_widths,
                              unsigned int dma_address
                              );
        ~synthesized_prints_t();
        virtual void init() {};
        virtual void tick();
        virtual bool terminate() { return false; };
        virtual int exit_code() { return 0; };
    private:
        PRINTWIDGET_struct * mmio_addrs;
        const unsigned int print_count;
        const unsigned int* print_offsets;
        const char* const*  format_strings;
        const unsigned int* argument_counts;
        const unsigned int* argument_widths;
        const unsigned int dma_address;

        const size_t token_bytes = 64; // One beat of the PCIS AXI4 bus
        const size_t batch_tokens = 16; // Number of tokens to pull each time
        using gmp_align_t = uint64_t;
        const size_t gmp_align_bits = sizeof(gmp_align_t) * 8;

        std::vector<std::vector<size_t>> widths;
        std::vector<size_t> sizes;
        std::vector<print_vars_t*> masks;

        std::vector<size_t> dw_aligned_offsets; //64 bit aligned
        std::vector<size_t> bit_offset;

        bool current_print_enabled(gmp_align_t* buf, size_t offset);
        void process_buffer(char * buf, size_t count);
        void show_prints(char * buf);
};

#endif // PRINTWIDGET_struct_guard

#endif //__SYNTHESIZED_PRINTS_H
