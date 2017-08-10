#include "simif.h"
#include <fstream>
#include <iostream>
#include <algorithm>

#ifdef ENABLE_SNAPSHOT
void simif_t::init_sampling(int argc, char** argv) {
  // Read mapping files
  sample_t::init_chains(std::string(TARGET_NAME) + ".chain");

  // Init sample variables
  sample_file = std::string(TARGET_NAME) + ".sample";
  sample_num = 30;
  last_sample = NULL;
  last_sample_id = 0;
  profile = false;
  sample_count = 0;
  sample_time = 0;
  tracelen = TRACE_MAX_LEN;
  trace_count = 0;

  std::vector<std::string> args(argv + 1, argv + argc);
  for (auto &arg: args) {
    if (arg.find("+sample=") == 0) {
      sample_file = arg.c_str() + 8;
    }
    if (arg.find("+samplenum=") == 0) {
      sample_num = strtol(arg.c_str() + 11, NULL, 10);
    }
    if (arg.find("+tracelen=") == 0) {
      tracelen = strtol(arg.c_str() + 10, NULL, 10);
    }
    if (arg.find("+profile") == 0) {
      profile = true;
    }
  }

  assert(tracelen > 2);
  write(TRACELEN_ADDR, tracelen);

#ifdef KEEP_SAMPLES_IN_MEM
  samples = new sample_t*[sample_num];
  for (size_t i = 0 ; i < sample_num ; i++) samples[i] = NULL;
#endif

  // flush output traces by sim reset
  for (size_t k = 0 ; k < OUT_TR_SIZE ; k++) {
    size_t addr = OUT_TR_ADDRS[k];
    size_t chunk = OUT_TR_CHUNKS[k];
    for (size_t off = 0 ; off < chunk ; off++)
      read(addr+off);
  }
  for (size_t id = 0, bits_id = 0 ; id < OUT_TR_READY_VALID_SIZE ; id++) {
    read((size_t)OUT_TR_READY_ADDRS[id]);
    bits_id = !read((size_t)OUT_TR_VALID_ADDRS[id]) ?
      bits_id + (size_t)OUT_TR_BITS_FIELD_NUMS[id] :
      trace_ready_valid_bits(NULL, false, id, bits_id);
  }

  if (profile) sim_start_time = timestamp();
}

void simif_t::finish_sampling() {
  // tail samples
  save_sample();

  // dump samples
  std::ofstream file(sample_file.c_str(), std::ios_base::out | std::ios_base::trunc);
  sample_t::dump_chains(file);
#ifdef KEEP_SAMPLES_IN_MEM
  for (size_t i = 0 ; i < sample_num ; i++) {
    if (samples[i] != NULL) {
      samples[i]->dump(file);
      delete samples[i];
    }
  }
  delete[] samples;
#else
  for (size_t i = 0 ; i < std::min(sample_num, sample_count) ; i++) {
    std::string fname = sample_file + "_" + std::to_string(i);
    std::ifstream f(fname.c_str());
    std::string line;
    while (std::getline(f, line)) {
      file << line << std::endl;
    }
#ifndef _WIN32
    remove(fname.c_str());
#endif
  }
#endif
  file.close();

  fprintf(stderr, "Sample Count: %zu\n", sample_count);
  if (profile) {
    double sim_time = diff_secs(timestamp(), sim_start_time);
    fprintf(stderr, "Simulation Time: %.3f s, Sample Time: %.3f s\n", 
                    sim_time, diff_secs(sample_time, 0));
  }
}

static const size_t data_t_chunks = sizeof(data_t) / sizeof(uint32_t);

size_t simif_t::trace_ready_valid_bits(sample_t* sample, bool poke, size_t id, size_t bits_id) {
  size_t bits_addr = poke ? (size_t)IN_TR_BITS_ADDRS[id] : (size_t)OUT_TR_BITS_ADDRS[id];
  size_t bits_chunk = poke ? (size_t)IN_TR_BITS_CHUNKS[id] : (size_t)OUT_TR_BITS_CHUNKS[id];
  size_t num_fields = poke ? (size_t)IN_TR_BITS_FIELD_NUMS[id] : (size_t)OUT_TR_BITS_FIELD_NUMS[id];
  data_t *bits_data = new data_t[bits_chunk];
  for (size_t off = 0 ; off < bits_chunk ; off++) {
    bits_data[off] = read(bits_addr + off);
  }
  if (sample) {
#ifndef _WIN32
    mpz_t data;
    mpz_init(data);
    mpz_import(data, bits_chunk, -1, sizeof(data_t), 0, 0, bits_data);
#else
    biguint_t data((uint32_t*)bits_data, bits_chunk * data_t_chunks);
#endif
    for (size_t k = 0, off = 0 ; k < num_fields ; k++, bits_id++) {
      size_t field_width = ((unsigned int*)(
        poke ? IN_TR_BITS_FIELD_WIDTHS : OUT_TR_BITS_FIELD_WIDTHS))[bits_id];
#ifndef _WIN32
      mpz_t *value = (mpz_t*)malloc(sizeof(mpz_t)), mask;
      mpz_inits(*value, mask, NULL);
      // value = data >> off
      mpz_fdiv_q_2exp(*value, data, off);
      // mask = (1 << field_width) - 1
      mpz_set_ui(mask, 1);
      mpz_mul_2exp(mask, mask, field_width);
      mpz_sub_ui(mask, mask, 1);
      // *value = *value & mask
      mpz_and(*value, *value, mask);
      mpz_clear(mask);
#else
      const size_t field_size = (field_width - 1) / (8 * sizeof(uint32_t)) + 1;
      const uint32_t field_mask = (1L << (field_width % (8 * sizeof(uint32_t)))) - 1;
      biguint_t temp = data >> off;
      uint32_t* field_data = new uint32_t[field_size];
      for (size_t i = 0 ; i < field_size ; i++) {
        field_data[i] = temp[i];
      }
      if (field_mask) field_data[field_size-1] &= field_mask;
      biguint_t *value = new biguint_t(field_data, field_size);
      delete[] field_data;
#endif
      sample->add_cmd(poke ?
        (sample_inst_t*) new poke_t(IN_TR_BITS, bits_id, value):
        (sample_inst_t*) new expect_t(OUT_TR_BITS, bits_id, value));
      off += field_width;
    }
#ifndef _WIN32
    mpz_clear(data);
#endif
  }

  delete[] bits_data;
  return bits_id;
}

sample_t* simif_t::read_traces(sample_t *sample) {
  for (size_t i = 0 ; i < std::min(trace_count, tracelen) ; i++) {
    // wire input traces from FPGA
    for (size_t id = 0 ; id < IN_TR_SIZE ; id++) {
      size_t addr = IN_TR_ADDRS[id];
      size_t chunk = IN_TR_CHUNKS[id];
      data_t *data = new data_t[chunk];
      for (size_t off = 0 ; off < chunk ; off++) {
        data[off] = read(addr+off);
      }
      if (sample) {
#ifndef _WIN32
        mpz_t *value = (mpz_t*)malloc(sizeof(mpz_t));
        mpz_init(*value);
        mpz_import(*value, chunk, -1, sizeof(data_t), 0, 0, data);
#else
        biguint_t *value = new biguint_t((uint32_t*)data, chunk * data_t_chunks);
#endif
        sample->add_cmd(new poke_t(IN_TR, id, value));
      }
      delete[] data;
    }

    // ready valid input traces from FPGA
    for (size_t id = 0, bits_id = 0 ; id < IN_TR_READY_VALID_SIZE ; id++) {
      size_t valid_addr = (size_t)IN_TR_VALID_ADDRS[id];
      data_t valid_data = read(valid_addr);
      if (sample) {
#ifndef _WIN32
        mpz_t* value = (mpz_t*)malloc(sizeof(mpz_t));
        mpz_init(*value);
        mpz_set_ui(*value, valid_data);
#else
        biguint_t* value = new biguint_t(valid_data);
#endif
        sample->add_cmd(new poke_t(IN_TR_VALID, id, value));
      }
      bits_id = !valid_data ?
        bits_id + (size_t)IN_TR_BITS_FIELD_NUMS[id] :
        trace_ready_valid_bits(sample, true, id, bits_id);
    }
    for (size_t id = 0 ; id < OUT_TR_READY_VALID_SIZE ; id++) {
      size_t ready_addr = (size_t)OUT_TR_READY_ADDRS[id];
      data_t ready_data = read(ready_addr);
      if (sample) {
#ifndef _WIN32
        mpz_t* value = (mpz_t*)malloc(sizeof(mpz_t));
        mpz_init(*value);
        mpz_set_ui(*value, ready_data);
#else
        biguint_t* value = new biguint_t(ready_data);
#endif
        sample->add_cmd(new poke_t(OUT_TR_READY, id, value));
      }
    }

    if (sample) sample->add_cmd(new step_t(1));

    // wire output traces from FPGA
    for (size_t id = 0 ; id < OUT_TR_SIZE ; id++) {
      size_t addr = OUT_TR_ADDRS[id];
      size_t chunk = OUT_TR_CHUNKS[id];
      data_t *data = new data_t[chunk];
      for (size_t off = 0 ; off < chunk ; off++) {
        data[off] = read(addr+off);
      }
      if (sample && i > 0) {
#ifndef _WIN32
        mpz_t *value = (mpz_t*)malloc(sizeof(mpz_t));
        mpz_init(*value);
        mpz_import(*value, chunk, -1, sizeof(data_t), 0, 0, data);
#else
        biguint_t *value = new biguint_t((uint32_t*)data, chunk * data_t_chunks);
#endif
        sample->add_cmd(new expect_t(OUT_TR, id, value));
      }
      delete[] data;
    }

    // ready valid output traces from FPGA
    for (size_t id = 0, bits_id = 0 ; id < OUT_TR_READY_VALID_SIZE ; id++) {
      size_t valid_addr = (size_t)OUT_TR_VALID_ADDRS[id];
      data_t valid_data = read(valid_addr);
      if (sample) {
#ifndef _WIN32
        mpz_t* value = (mpz_t*)malloc(sizeof(mpz_t));
        mpz_init(*value);
        mpz_set_ui(*value, valid_data);
#else
        biguint_t* value = new biguint_t(valid_data);
#endif
        sample->add_cmd(new expect_t(OUT_TR_VALID, id, value));
      }
      bits_id = !valid_data ?
        bits_id + (size_t)OUT_TR_BITS_FIELD_NUMS[id] :
        trace_ready_valid_bits(sample, false, id, bits_id);
    }
    for (size_t id = 0 ; id < IN_TR_READY_VALID_SIZE ; id++) {
      size_t ready_addr = (size_t)IN_TR_READY_ADDRS[id];
      data_t ready_data = read(ready_addr);
      if (sample) {
#ifndef _WIN32
        mpz_t* value = (mpz_t*)malloc(sizeof(mpz_t));
        mpz_init(*value);
        mpz_set_ui(*value, ready_data);
#else
        biguint_t* value = new biguint_t(ready_data);
#endif
        sample->add_cmd(new expect_t(IN_TR_READY, id, value));
      }
    }
  }

  return sample;
}

static inline char* int_to_bin(char *bin, data_t value, size_t size) {
  for (size_t i = 0 ; i < size; i++) {
    bin[i] = ((value >> (size-1-i)) & 0x1) + '0';
  }
  bin[size] = 0;
  return bin;
}

sample_t* simif_t::read_snapshot() {
  std::ostringstream snap;
  char bin[DAISY_WIDTH+1];
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    CHAIN_TYPE type = static_cast<CHAIN_TYPE>(t);
    const size_t chain_loop = sample_t::get_chain_loop(type);
    const size_t chain_len = sample_t::get_chain_len(type);
    for (size_t k = 0 ; k < chain_loop ; k++) {
      for (size_t i = 0 ; i < CHAIN_SIZE[t] ; i++) {
        switch(type) {
          case SRAM_CHAIN:
            write(SRAM_RESTART_ADDR + i, 1);
            break;
          case REGFILE_CHAIN:
            write(REGFILE_RESTART_ADDR + i, 1);
            break;
          default:
            break;
        }
        for (size_t j = 0 ; j < chain_len ; j++) {
          snap << int_to_bin(bin, read(CHAIN_ADDR[t] + i), DAISY_WIDTH);
        }
      }
    }
  }
  return new sample_t(snap.str().c_str(), cycles());
}

void simif_t::save_sample() {
  if (last_sample != NULL) {
    sample_t* sample = read_traces(last_sample);
#ifdef KEEP_SAMPLES_IN_MEM
    if (samples[last_sample_id] != NULL)
      delete samples[last_sample_id];
    samples[last_sample_id] = sample;
#else
    std::string filename = sample_file + "_" + std::to_string(last_sample_id);
    std::ofstream file(filename.c_str(), std::ios_base::out | std::ios_base::trunc);
    sample->dump(file);
    delete sample;
    file.close();
#endif
  }
}

void simif_t::reservoir_sampling(size_t n) {
  if (t % tracelen == 0) {
    midas_time_t start_time = 0;
    uint64_t record_id = t / tracelen;
    uint64_t sample_id = record_id < sample_num ? record_id : gen() % (record_id + 1);
    if (sample_id < sample_num) {
      sample_count++;
      if (profile) start_time = timestamp();
      save_sample();
      last_sample = read_snapshot();
      last_sample_id = sample_id;
      trace_count = 0;
      if (profile) sample_time += (timestamp() - start_time);
    }
  }
  if (trace_count < tracelen) trace_count += n;
}
#endif
