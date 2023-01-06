// See LICENSE for license details.

#include "simif_emul.h"

#include "bridges/cpu_managed_stream.h"
#include "bridges/fpga_managed_stream.h"

simif_emul_t::simif_emul_t(const TargetConfig &config,
                           const std::vector<std::string> &args)
    : simif_t(config, args), master(std::make_unique<mmio_t>(config.ctrl)) {

  memsize = 1L << config.mem.addr_bits;

  // Parse arguments.
  for (auto arg : args) {
    if (arg.find("+waveform=") == 0) {
      waveform = arg.c_str() + 10;
    }
    if (arg.find("+fastloadmem") == 0) {
      fastloadmem = true;
    }
    if (arg.find("+memsize=") == 0) {
      memsize = strtoll(arg.c_str() + 9, NULL, 10);
    }
    if (arg.find("+fuzz-host-timing=") == 0) {
      maximum_host_delay = atoi(arg.c_str() + 18);
    }
    if (arg.find("+fuzz-seed=") == 0) {
      fuzz_seed = strtoll(arg.c_str() + 11, NULL, 10);
      fprintf(stderr, "Using custom fuzzer seed: %ld\n", fuzz_seed);
    }
  }
  fuzz_gen.seed(fuzz_seed);

  // Initialise memories.
  for (unsigned i = 0; i < config.mem_num_channels; ++i) {
    slave.emplace_back(std::make_unique<mm_magic_t>(config.mem));
    (*slave.rbegin())->init(memsize, 64);
  }

  using namespace std::placeholders;
  auto mmio_read_func = std::bind(&simif_emul_t::read, this, _1);
  auto mmio_write_func = std::bind(&simif_emul_t::write, this, _1, _2);

  if (auto conf = config.cpu_managed) {
    cpu_managed_axi4.reset(new mmio_t(*conf));
#ifdef CPUMANAGEDSTREAMENGINE_0_PRESENT
    auto cpu_managed_axi4_read_func =
        std::bind(&simif_emul_t::cpu_managed_axi4_read, this, _1, _2, _3);
    auto cpu_managed_axi4_write_func =
        std::bind(&simif_emul_t::cpu_managed_axi4_write, this, _1, _2, _3);

    for (size_t i = 0; i < CPUMANAGEDSTREAMENGINE_0_from_cpu_stream_count;
         i++) {
      auto params = CPUManagedStreams::StreamParameters(
          std::string(CPUMANAGEDSTREAMENGINE_0_from_cpu_names[i]),
          CPUMANAGEDSTREAMENGINE_0_from_cpu_dma_addrs[i],
          CPUMANAGEDSTREAMENGINE_0_from_cpu_count_addrs[i],
          CPUMANAGEDSTREAMENGINE_0_from_cpu_buffer_sizes[i]);

      cpu_to_fpga_streams.push_back(
          std::make_unique<CPUManagedStreams::CPUToFPGADriver>(
              params, *conf, mmio_read_func, cpu_managed_axi4_write_func));
    }

    for (size_t i = 0; i < CPUMANAGEDSTREAMENGINE_0_to_cpu_stream_count; i++) {
      auto params = CPUManagedStreams::StreamParameters(
          std::string(CPUMANAGEDSTREAMENGINE_0_to_cpu_names[i]),
          CPUMANAGEDSTREAMENGINE_0_to_cpu_dma_addrs[i],
          CPUMANAGEDSTREAMENGINE_0_to_cpu_count_addrs[i],
          CPUMANAGEDSTREAMENGINE_0_to_cpu_buffer_sizes[i]);

      fpga_to_cpu_streams.push_back(
          std::make_unique<CPUManagedStreams::FPGAToCPUDriver>(
              params, *conf, mmio_read_func, cpu_managed_axi4_read_func));
    }
#endif // CPUMANAGEDSTREAMENGINE_0_PRESENT
  }

  if (auto conf = config.fpga_managed) {
    // The final parameter, line size, is not used under mm_magic_t
    cpu_mem.reset(new mm_magic_t(*conf));
    cpu_mem->init((1ULL << conf->addr_bits), 512);

#ifdef FPGAMANAGEDSTREAMENGINE_0_PRESENT
    auto fpga_address_memory_base = ((char *)cpu_mem->get_data());
    auto offset = 0;

    for (size_t i = 0; i < FPGAMANAGEDSTREAMENGINE_0_to_cpu_stream_count; i++) {
      auto params = FPGAManagedStreams::StreamParameters(
          std::string(FPGAMANAGEDSTREAMENGINE_0_to_cpu_names[i]),
          FPGAMANAGEDSTREAMENGINE_0_to_cpu_fpgaBufferDepth[i],
          FPGAMANAGEDSTREAMENGINE_0_to_cpu_toHostPhysAddrHighAddrs[i],
          FPGAMANAGEDSTREAMENGINE_0_to_cpu_toHostPhysAddrLowAddrs[i],
          FPGAMANAGEDSTREAMENGINE_0_to_cpu_bytesAvailableAddrs[i],
          FPGAMANAGEDSTREAMENGINE_0_to_cpu_bytesConsumedAddrs[i],
          FPGAMANAGEDSTREAMENGINE_0_to_cpu_toHostStreamDoneInitAddrs[i],
          FPGAMANAGEDSTREAMENGINE_0_to_cpu_toHostStreamFlushAddrs[i],
          FPGAMANAGEDSTREAMENGINE_0_to_cpu_toHostStreamFlushDoneAddrs[i]);

      fpga_to_cpu_streams.push_back(
          std::make_unique<FPGAManagedStreams::FPGAToCPUDriver>(
              params,
              (void *)(fpga_address_memory_base + offset),
              offset,
              mmio_read_func,
              mmio_write_func));
      offset += params.buffer_capacity;
    }
#endif // FPGAMANAGEDSTREAMENGINE_0_PRESENT
  }

  // Set up the simulation thread.
  finished = false;
  exit_code = EXIT_FAILURE;
  {
    std::unique_lock<std::mutex> lock(rtlsim_mutex);
    driver_flag = false;
    rtlsim_flag = false;
    thread = std::thread([&] { thread_main(); });
    rtlsim_cond.wait(lock, [&] { return rtlsim_flag; });
  }
}

simif_emul_t::~simif_emul_t() {}

void simif_emul_t::host_mmio_init() {
  for (auto &stream : this->fpga_to_cpu_streams) {
    stream->init();
  }
  for (auto &stream : this->cpu_to_fpga_streams) {
    stream->init();
  }
}

void simif_emul_t::wait_write(mmio_t &mmio) {
  while (!mmio.write_resp())
    advance_target();
}

void simif_emul_t::wait_read(mmio_t &mmio, void *data) {
  while (!mmio.read_resp(data))
    advance_target();
}

void simif_emul_t::write(size_t addr, uint32_t data) {
  uint64_t size = master->get_config().get_size();
  assert(size == 2 && "AXI4-lite control interface has unexpected size");
  uint64_t strb = (1 << master->get_config().strb_bits()) - 1;
  master->write_req(addr, size, 0, &data, &strb);
  wait_write(*master);
}

uint32_t simif_emul_t::read(size_t addr) {
  uint64_t size = master->get_config().get_size();
  assert(size == 2 && "AXI4-lite control interface has unexpected size");
  uint32_t data;
  master->read_req(addr, size, 0);
  wait_read(*master, &data);
  return data;
}

#define MAX_LEN 255

size_t simif_emul_t::pull(unsigned stream_idx,
                          void *dest,
                          size_t num_bytes,
                          size_t threshold_bytes) {
  assert(stream_idx < fpga_to_cpu_streams.size());
  return this->fpga_to_cpu_streams[stream_idx]->pull(
      dest, num_bytes, threshold_bytes);
}

size_t simif_emul_t::push(unsigned stream_idx,
                          void *src,
                          size_t num_bytes,
                          size_t threshold_bytes) {
  assert(stream_idx < cpu_to_fpga_streams.size());
  return this->cpu_to_fpga_streams[stream_idx]->push(
      src, num_bytes, threshold_bytes);
}

void simif_emul_t::pull_flush(unsigned stream_idx) {
  assert(stream_idx < fpga_to_cpu_streams.size());
  return this->fpga_to_cpu_streams[stream_idx]->flush();
}

void simif_emul_t::push_flush(unsigned stream_idx) {
  assert(stream_idx < cpu_to_fpga_streams.size());
  return this->cpu_to_fpga_streams[stream_idx]->flush();
}

size_t
simif_emul_t::cpu_managed_axi4_read(size_t addr, char *data, size_t size) {
  const uint64_t beat_bytes = config.cpu_managed->beat_bytes();
  ssize_t len = (size - 1) / beat_bytes;

  while (len >= 0) {
    size_t part_len = len % (MAX_LEN + 1);

    cpu_managed_axi4->read_req(addr, log2(beat_bytes), part_len);
    wait_read(*cpu_managed_axi4, data);

    len -= (part_len + 1);
    addr += (part_len + 1) * beat_bytes;
    data += (part_len + 1) * beat_bytes;
  }
  return size;
}

size_t
simif_emul_t::cpu_managed_axi4_write(size_t addr, char *data, size_t size) {
  const uint64_t beat_bytes = config.cpu_managed->beat_bytes();
  ssize_t len = (size - 1) / beat_bytes;
  const size_t remaining = size - len * beat_bytes;
  size_t strb[len + 1];
  size_t *strb_ptr = &strb[0];

  for (int i = 0; i < len; i++) {
    strb[i] = beat_bytes > 63 ? -1 : ((1LL << beat_bytes) - 1);
  }

  if (remaining == beat_bytes)
    strb[len] = strb[0];
  else
    strb[len] = (1LL << remaining) - 1;

  while (len >= 0) {
    const size_t part_len = len % (MAX_LEN + 1);

    cpu_managed_axi4->write_req(
        addr, log2(beat_bytes), part_len, data, strb_ptr);
    wait_write(*cpu_managed_axi4);

    len -= (part_len + 1);
    addr += (part_len + 1) * beat_bytes;
    data += (part_len + 1) * beat_bytes;
    strb_ptr += (part_len + 1);
  }

  return size;
}

void simif_emul_t::load_mems(const char *fname) {
  slave[0]->load_mem(0, fname);
  // TODO: allow file to be split across slaves
}

int simif_emul_t::end() {
  assert(finished && "simulation not yet finished");
  thread.join();
  return exit_code;
}

void simif_emul_t::do_tick() {
  driver_flag = false;
  rtlsim_flag = true;

  {
    std::unique_lock<std::mutex> lock(rtlsim_mutex);
    rtlsim_cond.notify_one();
  }
  {
    std::unique_lock<std::mutex> lock(driver_mutex);
    driver_cond.wait(lock, [&] { return driver_flag; });
  }
}

bool simif_emul_t::to_sim() {
  rtlsim_flag = false;
  driver_flag = true;

  {
    std::unique_lock<std::mutex> lock(driver_mutex);
    driver_cond.notify_one();
  }
  {
    std::unique_lock<std::mutex> lock(rtlsim_mutex);
    rtlsim_cond.wait(lock, [&] { return rtlsim_flag || finished; });
  }
  return finished;
}

void simif_emul_t::thread_main() {
  // Switch over to the target thread and wait for the first tick.
  do_tick();

  // Load memories before initialising the simulation.
  if (fastloadmem && !load_mem_path.empty()) {
    fprintf(stdout, "[fast loadmem] %s\n", load_mem_path.c_str());
    load_mems(load_mem_path.c_str());
  }

  // Run the simulation flow.
  exit_code = simulation_run();

  // Wake the target thread before returning from the simulation thread.
  {
    std::unique_lock<std::mutex> lock(rtlsim_mutex);
    finished = true;
    rtlsim_cond.notify_one();
  }
}
