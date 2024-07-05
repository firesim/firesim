// See LICENSE for license details.

#include "simif_emul.h"

#include "bridges/cpu_managed_stream.h"
#include "bridges/fpga_managed_stream.h"
#include "core/simulation.h"
#include "emul/mm.h"
#include "emul/mmio.h"
#include <cassert>
#include <memory>
#include <string.h>
#include <unistd.h>

simif_emul_t::simif_emul_t(const TargetConfig &config,
                           const std::vector<std::string> &args)
    : simif_t(config), master(std::make_unique<mmio_t>(config.ctrl)) {

  // Parse arguments.
  memsize = 1L << config.mem.addr_bits;
  bool fastloadmem = false;
  int fpga_cnt = 1;
  int fpga_idx = 0;
  bool noc_part = false;
  bool comb_logic = false;
  for (auto arg : args) {
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
    if (arg.find("+loadmem=") == 0) {
      load_mem_path = arg.c_str() + 9;
    }
    if (arg.find("+partition-fpga-cnt=") == 0) {
      fpga_cnt = atoi(arg.c_str() + 20);
    }
    if (arg.find("+partition-fpga-idx=") == 0) {
      fpga_idx = atoi(arg.c_str() + 20);
    }
    if (arg.find("+partition-fpga-topo=") == 0) {
      int fpga_topo_str = atoi(arg.c_str() + 21);
      if (fpga_topo_str == 1)
        comb_logic = true;
      else if (fpga_topo_str == 2)
        noc_part = true;
    }
  }

  for (int i = 0; i < config.qsfp.num_channels; i++) {
    qsfp.emplace_back(std::make_unique<qsfp_t>(config.qsfp));
  }

  printf("qsfp_num_chans: %d\n", config.qsfp.num_channels);

  for (int idx = 0; idx < config.qsfp.num_channels; idx++) {
    char qsfp_owned_name[257];
    char qsfp_other_name[257];
    int next_fpga_idx = (fpga_idx + 1) % fpga_cnt;
    int prev_fpga_idx = (fpga_idx + fpga_cnt - 1) % fpga_cnt;

    printf("noc_part: %d comb_logic: %d\n", noc_part, comb_logic);

    // TODO : add argument in firesim manager
    if (!noc_part)
      assert(fpga_cnt <= 3);

    // If this is FPGA-0, reverse the qsfp channel shmem setup order to
    // avoid deadlocks
    int qsfp_chan =
        (fpga_idx == 0) ? idx : (config.qsfp.num_channels - 1 - idx);

    if (comb_logic) {
      qsfp_chan = idx;
    }

    if (fpga_cnt <= 2) {
      sprintf(qsfp_owned_name,
              "/qsfp%03d_%03d_to_%03d",
              qsfp_chan,
              fpga_idx,
              next_fpga_idx);
      sprintf(qsfp_other_name,
              "/qsfp%03d_%03d_to_%03d",
              qsfp_chan,
              next_fpga_idx,
              fpga_idx);
    } else if (noc_part) {
      if (qsfp_chan == 0) {
        sprintf(qsfp_owned_name, "/qsfp_%02d_to_%02d", fpga_idx, prev_fpga_idx);
        sprintf(qsfp_other_name, "/qsfp_%02d_to_%02d", prev_fpga_idx, fpga_idx);
      } else {
        sprintf(qsfp_owned_name, "/qsfp_%02d_to_%02d", fpga_idx, next_fpga_idx);
        sprintf(qsfp_other_name, "/qsfp_%02d_to_%02d", next_fpga_idx, fpga_idx);
      }
    } else {
      if (qsfp_chan == 0 && fpga_idx != 0) {
        sprintf(qsfp_owned_name, "/qsfp_%02d_to_%02d", fpga_idx, next_fpga_idx);
        sprintf(qsfp_other_name, "/qsfp_%02d_to_%02d", next_fpga_idx, fpga_idx);
      }
      if (qsfp_chan == 0 && fpga_idx == 0) {
        sprintf(qsfp_owned_name, "/qsfp_%02d_to_%02d", fpga_idx, prev_fpga_idx);
        sprintf(qsfp_other_name, "/qsfp_%02d_to_%02d", prev_fpga_idx, fpga_idx);
      }
      if (qsfp_chan == 1 && fpga_idx != 0) {
        sprintf(qsfp_owned_name, "/qsfp_%02d_to_%02d", fpga_idx, prev_fpga_idx);
        sprintf(qsfp_other_name, "/qsfp_%02d_to_%02d", prev_fpga_idx, fpga_idx);
      }
      if (qsfp_chan == 1 && fpga_idx == 0) {
        sprintf(qsfp_owned_name, "/qsfp_%02d_to_%02d", fpga_idx, next_fpga_idx);
        sprintf(qsfp_other_name, "/qsfp_%02d_to_%02d", next_fpga_idx, fpga_idx);
      }
    }
    qsfp[qsfp_chan]->setup_shmem(qsfp_owned_name, qsfp_other_name);
  }

  if (!fastloadmem)
    load_mem_path.clear();

  fuzz_gen.seed(fuzz_seed);

  // Initialise memories.
  for (unsigned i = 0; i < config.mem_num_channels; ++i) {
    slave.emplace_back(std::make_unique<mm_magic_t>(config.mem));
    (*slave.rbegin())->init(memsize, 64);
  }

  // Set up the managed stream.
  if (std::optional<AXI4Config> conf = config.cpu_managed) {
    assert(!config.fpga_managed && "stream should be CPU or FPGA managed");
    cpu_managed_stream_io.reset(new CPUManagedStreamIOImpl(*this, *conf));
  }

  if (std::optional<AXI4Config> conf = config.fpga_managed) {
    assert(!config.cpu_managed && "stream should be CPU or FPGA managed");
    fpga_managed_stream_io.reset(new FPGAManagedStreamIOImpl(*this, *conf));
  }

  std::cout << "simif_emul_t: done" << std::endl;
}

simif_emul_t::~simif_emul_t() {}

void simif_emul_t::start_driver(simulation_t &sim) {
  // Set up the simulation thread.
  finished = false;
  exit_code = EXIT_FAILURE;
  {
    std::unique_lock<std::mutex> lock(rtlsim_mutex);
    driver_flag = false;
    rtlsim_flag = false;

    // Spawn the target thread.
    thread = std::thread([&] {
      do_tick();

      // Load memories before initialising the simulation.
      if (!load_mem_path.empty()) {
        fprintf(stdout,
                "[fast loadmem] loadmem path exists, load simulation memories "
                "without loadmem_t widget, path = %s\n",
                load_mem_path.c_str());
        load_mems(load_mem_path.c_str());
      }

      // Run the simulation flow.
      exit_code = sim.execute_simulation_flow();

      // Wake the target thread before returning from the simulation thread.
      {
        std::unique_lock<std::mutex> lock(rtlsim_mutex);
        finished = true;
        rtlsim_cond.notify_one();
      }
    });

    // Wait for the target thread to yield and enter the RTL simulator.
    // The target thread is waken up when the DPI tick function is
    // ready to transfer data to it.
    rtlsim_cond.wait(lock, [&] { return rtlsim_flag; });
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

size_t simif_emul_t::CPUManagedStreamIOImpl::cpu_managed_axi4_read(
    size_t addr, char *data, size_t size) {
  const uint64_t beat_bytes = cpu_managed_axi4.get_config().beat_bytes();
  ssize_t len = (size - 1) / beat_bytes;

  while (len >= 0) {
    size_t part_len = len % (MAX_LEN + 1);

    cpu_managed_axi4.read_req(addr, log2(beat_bytes), part_len);
    simif.wait_read(cpu_managed_axi4, data);

    len -= (part_len + 1);
    addr += (part_len + 1) * beat_bytes;
    data += (part_len + 1) * beat_bytes;
  }
  return size;
}

size_t simif_emul_t::CPUManagedStreamIOImpl::cpu_managed_axi4_write(
    size_t addr, const char *data, size_t size) {
  const uint64_t beat_bytes = cpu_managed_axi4.get_config().beat_bytes();
  ssize_t len = (size - 1) / beat_bytes;
  const size_t remaining = size - len * beat_bytes;
  size_t strb[len + 1];
  size_t *strb_ptr = &strb[0];

  for (int i = 0; i < len; i++) {
    strb[i] = beat_bytes > 63 ? -1 : ((1LL << beat_bytes) - 1);
  }

  if (remaining == beat_bytes && len > 0)
    strb[len] = strb[0];
  else if (remaining == beat_bytes)
    strb[len] = -1;
  else
    strb[len] = (1LL << remaining) - 1;

  while (len >= 0) {
    const size_t part_len = len % (MAX_LEN + 1);

    cpu_managed_axi4.write_req(
        addr, log2(beat_bytes), part_len, data, strb_ptr);
    simif.wait_write(cpu_managed_axi4);

    len -= (part_len + 1);
    addr += (part_len + 1) * beat_bytes;
    data += (part_len + 1) * beat_bytes;
    strb_ptr += (part_len + 1);
  }

  return size;
}

simif_emul_t::FPGAManagedStreamIOImpl::FPGAManagedStreamIOImpl(
    simif_emul_t &simif, const AXI4Config &config)
    : simif(simif), cpu_mem(config) {
  // The final parameter, line size, is not used under mm_magic_t
  cpu_mem.init((1ULL << cpu_mem.get_config().addr_bits), 512);
}

void simif_emul_t::load_mems(const char *fname) {
  // TODO: allow file to be split across slaves
  if (!slave.empty()) {
    slave[0]->load_mem(0, fname);
  }
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
