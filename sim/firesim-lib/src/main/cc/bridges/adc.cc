//See LICENSE for license details
#ifdef ADCBRIDGEMODULE_struct_guard

#include "adc.h"

#include <stdio.h>
#include <string.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <cmath>

adc_t::adc_t(simif_t *sim, std::vector<std::string> &args,
        ADCBRIDGEMODULE_struct *mmio_addrs, uint32_t (*signal_func)(uint64_t) ): bridge_driver_t(sim)
{
    this->mmio_addrs = mmio_addrs;
    this->sampling_freq = 0;
    this->adc_bits = 0;


    // construct arg parsing strings here. We basically append the bridge_driver
    // number to each of these base strings, to get args like +blkdev0 etc.
    std::string num_equals = std::to_string(adcno) + std::string("=");
    std::string sampling_freq_arg = std::string("+adc-sample-freq") + num_equals;
    std::string adc_bits_arg = std::string("+adc-bits") + num_equals;

    for (auto &arg: args) {
        if (arg.find(sampling_freq_arg) == 0) {
            char *str = const_cast<char*>(arg.c_str()) + sampling_freq_arg.length();
            sampling_freq = atoi(str);
        }
        if (arg.find(adc_bits_arg) == 0) {
            char *str = const_cast<char*>(arg.c_str()) + adc_bits_arg.length();
            adc_bits = atoi(str);
        }
    }

    if (signal_func != NULL) {
      this->signal_func = sin_fixed_point;
    } else {
      this->signal_func = signal_func; 
    }

}

adc_t::sin_fixed_point(uint64_t t, int scale_bits) {
    float x = sin(t);
    uint64_t scale = (1 << scale_bits) - 1;
    uint64_t quant = (uint64_t) round((x+1)*scale);
    return quant;
}


adc_t::~adc_t() {
    for (int j = 0; j < 2; j++) {
        munmap(pcis_write_bufs[j], BUFBYTES+EXTRABYTES);
    }
    free(this->mmio_addrs);
}


void adc_t::init() {

    uint32_t input_token_capacity = ADC_BUFSIZE - read(mmio_addrs->incoming_count);

    printf("On init, %d token slots available on input.\n", input_token_capacity);
    uint32_t token_bytes_produced = 0;
    token_bytes_produced = push(
            dma_addr,
            pcis_write_bufs[1],
            BUFWIDTH*input_token_capacity);
    if (token_bytes_produced != input_token_capacity*BUFWIDTH) {
        printf("ERR MISMATCH!\n");
        exit(1);
    }

    return;
}


void adc_t::tick() {

    //token handling
    while (true) { // break when we don't have 5k tokens
        uint32_t input_token_capacity = ADC_BUFSIZE - read(mmio_addrs->incoming_count);

        if (input_token_capacity != ADC_BUFSIZE) {
            return;
        }

        //generate a batch of analog signal value (batch size is
        for (int sample_i = timeelapsed_cycles; sample_i < timeelapsed_cycles + ADC_BUFSIZE; ++sample_i) 
        {
          double t = sample_i / (double)(sampling_freq);
          uint32_t sample_value = signal_func(t);

          //quantize the sample base on ADC bits
          uint32_t signal_range = 2; //THis is for the case of sin. We can require all signals to be normalized to -1,1
          uint64_t scale = (1 << adc_bits) - 1;
          uint64_t quant_sample_value = (uint64_t) round((sample_value+(signal_range / 2))*scale);
          pcis_write_bufs[currentround][sample_i] = quant_sample_value;
        }

        timeelapsed_cycles =+ ADC_BUFSIZE
        volatile uint8_t * polladdr = (uint8_t*)(pcis_write_bufs[currentround] + BUFBYTES);
        while (*polladdr == 0) { ; }

        uint32_t token_bytes_sent_to_fpga = 0;
        token_bytes_sent_to_fpga = push(
                dma_addr,
                pcis_write_bufs[currentround],
                BUFWIDTH * tokens_this_round);
        pcis_write_bufs[currentround][BUFBYTES] = 0;
        if (token_bytes_sent_to_fpga != tokens_this_round * BUFWIDTH) {
            printf("ERR MISMATCH! on writing tokens in. actually wrote in %d bytes, wanted %d bytes.\n", token_bytes_sent_to_fpga, BUFWIDTH * tokens_this_round);
            printf("errno: %s\n", strerror(errno));
            exit(1);
        }

        currentround = (currentround + 1) % 2;

}

#endif // #ifdef ADCBRIDGEMODULE_struct_guard

