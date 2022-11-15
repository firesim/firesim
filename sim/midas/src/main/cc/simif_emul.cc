// See LICENSE for license details.

#include "simif_emul.h"
#ifdef VCS
#include "emul/vcs_main.h"
#include <emul/context.h>
#else
#include <verilated.h>
#if VM_TRACE
#include <verilated_vcd_c.h>
#endif
#endif
#include <signal.h>

#include "bridges/cpu_managed_stream.h"

uint64_t main_time = 0;

#ifdef VCS
context_t *host;
context_t target;
bool vcs_rst = false;
bool vcs_fin = false;
#else
Vverilator_top *top = NULL;
#if VM_TRACE
VerilatedVcdC *tfp = NULL;
#endif // VM_TRACE
double sc_time_stamp() { return (double)main_time; }
extern void tick();
#endif // VCS

mmio_t *simif_emul_t::master = new mmio_t(CTRL_BEAT_BYTES);
mmio_t *simif_emul_t::cpu_managed_axi4 =
    new mmio_t(CPU_MANAGED_AXI4_BEAT_BYTES);

mm_t *simif_emul_t::slave[MEM_NUM_CHANNELS] = {nullptr};

mm_t *simif_emul_t::cpu_mem = new mm_magic_t;

void finish() {
#ifdef VCS
  vcs_fin = true;
  target.switch_to();
#else
#if VM_TRACE
  if (tfp)
    tfp->close();
  delete tfp;
#endif // VM_TRACE
#endif // VCS
}

void handle_sigterm(int sig) { finish(); }

simif_emul_t::simif_emul_t() {

#ifdef FPGA_MANAGED_AXI4_PRESENT
  // The final parameter, line size, is not used under mm_magic_t
  cpu_mem->init((1ULL << FPGA_MANAGED_AXI4_ADDR_BITS),
                FPGA_MANAGED_AXI4_DATA_BITS / 8,
                512);
#endif

  using namespace std::placeholders;
  auto mmio_read_func = std::bind(&simif_emul_t::read, this, _1);

#ifdef CPUMANAGEDSTREAMENGINE_0_PRESENT
  auto cpu_managed_axi4_read_func =
      std::bind(&simif_emul_t::cpu_managed_axi4_read, this, _1, _2, _3);
  auto cpu_managed_axi4_write_func =
      std::bind(&simif_emul_t::cpu_managed_axi4_write, this, _1, _2, _3);

  for (size_t i = 0; i < CPUMANAGEDSTREAMENGINE_0_from_cpu_stream_count; i++) {
    auto params = CPUManagedStreamParameters(
        std::string(CPUMANAGEDSTREAMENGINE_0_from_cpu_names[i]),
        CPUMANAGEDSTREAMENGINE_0_from_cpu_dma_addrs[i],
        CPUMANAGEDSTREAMENGINE_0_from_cpu_count_addrs[i],
        CPUMANAGEDSTREAMENGINE_0_from_cpu_buffer_sizes[i]);

    from_host_streams.push_back(
        StreamFromCPU(params, mmio_read_func, cpu_managed_axi4_write_func));
  }

  for (size_t i = 0; i < CPUMANAGEDSTREAMENGINE_0_to_cpu_stream_count; i++) {
    auto params = CPUManagedStreamParameters(
        std::string(CPUMANAGEDSTREAMENGINE_0_to_cpu_names[i]),
        CPUMANAGEDSTREAMENGINE_0_to_cpu_dma_addrs[i],
        CPUMANAGEDSTREAMENGINE_0_to_cpu_count_addrs[i],
        CPUMANAGEDSTREAMENGINE_0_to_cpu_buffer_sizes[i]);

    to_host_streams.push_back(
        StreamToCPU(params, mmio_read_func, cpu_managed_axi4_read_func));
  }
#endif // CPUMANAGEDSTREAMENGINE_0_PRESENT
}

simif_emul_t::~simif_emul_t(){};

void simif_emul_t::host_init(int argc, char **argv) {
  // Parse args
  std::vector<std::string> args(argv + 1, argv + argc);
  std::string waveform = "dump.vcd";
  std::string loadmem;
  bool fastloadmem = false;
  uint64_t memsize = 1L << MEM_ADDR_BITS;
  for (auto arg : args) {
    if (arg.find("+waveform=") == 0) {
      waveform = arg.c_str() + 10;
    }
    if (arg.find("+loadmem=") == 0) {
      loadmem = arg.c_str() + 9;
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
  }

  for (int mem_channel_index = 0; mem_channel_index < MEM_NUM_CHANNELS;
       mem_channel_index++) {
    slave[mem_channel_index] = (mm_t *)new mm_magic_t;
    slave[mem_channel_index]->init(memsize, MEM_BEAT_BYTES, 64);
  }

  if (fastloadmem && !loadmem.empty()) {
    fprintf(stdout, "[fast loadmem] %s\n", loadmem.c_str());
    load_mems(loadmem.c_str());
  }

  signal(SIGTERM, handle_sigterm);
#ifdef VCS
  host = context_t::current();
  target_args_t *targs = new target_args_t(argc, argv);
  target.init(target_thread, targs);
  vcs_rst = true;
  for (size_t i = 0; i < 10; i++)
    target.switch_to();
  vcs_rst = false;
#else
  Verilated::commandArgs(argc, argv); // Remember args

  top = new Vverilator_top;
#if VM_TRACE // If emul was invoked with --trace
  tfp = new VerilatedVcdC;
  Verilated::traceEverOn(true); // Verilator must compute traced signals
  VL_PRINTF("Enabling waves: %s\n", waveform.c_str());
  top->trace(tfp, 99);         // Trace 99 levels of hierarchy
  tfp->open(waveform.c_str()); // Open the dump file
#endif       // VM_TRACE

  top->reset = 1;
  for (size_t i = 0; i < 10; i++)
    ::tick();
  top->reset = 0;
#endif
}

int simif_emul_t::host_finish() {
  ::finish();
  return 0;
}

void simif_emul_t::advance_target() {
  int cycles_to_wait = rand_next(maximum_host_delay) + 1;
  for (int i = 0; i < cycles_to_wait; i++) {
#ifdef VCS
    target.switch_to();
#else
    ::tick();
#endif
  }
}

void simif_emul_t::wait_write(mmio_t *mmio) {
  while (!mmio->write_resp())
    advance_target();
}

void simif_emul_t::wait_read(mmio_t *mmio, void *data) {
  while (!mmio->read_resp(data))
    advance_target();
}

void simif_emul_t::write(size_t addr, uint32_t data) {
  size_t strb = (1 << CTRL_STRB_BITS) - 1;
  static_assert(CTRL_AXI4_SIZE == 2,
                "AXI4-lite control interface has unexpected size");
  master->write_req(addr, CTRL_AXI4_SIZE, 0, &data, &strb);
  wait_write(master);
}

uint32_t simif_emul_t::read(size_t addr) {
  uint32_t data;
  master->read_req(addr, CTRL_AXI4_SIZE, 0);
  wait_read(master, &data);
  return data;
}

#define MAX_LEN 255

size_t simif_emul_t::pull(unsigned stream_idx,
                          void *dest,
                          size_t num_bytes,
                          size_t threshold_bytes) {
  assert(stream_idx < to_host_streams.size());
  return this->to_host_streams[stream_idx].pull(
      dest, num_bytes, threshold_bytes);
}

size_t simif_emul_t::push(unsigned stream_idx,
                          void *src,
                          size_t num_bytes,
                          size_t threshold_bytes) {
  assert(stream_idx < from_host_streams.size());
  return this->from_host_streams[stream_idx].push(
      src, num_bytes, threshold_bytes);
}

size_t
simif_emul_t::cpu_managed_axi4_read(size_t addr, char *data, size_t size) {
  ssize_t len = (size - 1) / CPU_MANAGED_AXI4_BEAT_BYTES;

  while (len >= 0) {
    size_t part_len = len % (MAX_LEN + 1);

    cpu_managed_axi4->read_req(
        addr, log2(CPU_MANAGED_AXI4_BEAT_BYTES), part_len);
    wait_read(cpu_managed_axi4, data);

    len -= (part_len + 1);
    addr += (part_len + 1) * CPU_MANAGED_AXI4_BEAT_BYTES;
    data += (part_len + 1) * CPU_MANAGED_AXI4_BEAT_BYTES;
  }
  return size;
}

size_t
simif_emul_t::cpu_managed_axi4_write(size_t addr, char *data, size_t size) {
  ssize_t len = (size - 1) / CPU_MANAGED_AXI4_BEAT_BYTES;
  size_t remaining = size - len * CPU_MANAGED_AXI4_BEAT_BYTES;
  size_t strb[len + 1];
  size_t *strb_ptr = &strb[0];

  for (int i = 0; i < len; i++) {
#if CPU_MANAGED_AXI4_BEAT_BYTES == 64
    strb[i] = -1;
#else
    strb[i] = (1LL << CPU_MANAGED_AXI4_BEAT_BYTES) - 1;
#endif
  }

  if (remaining == CPU_MANAGED_AXI4_BEAT_BYTES)
    strb[len] = strb[0];
  else
    strb[len] = (1LL << remaining) - 1;

  while (len >= 0) {
    size_t part_len = len % (MAX_LEN + 1);

    cpu_managed_axi4->write_req(
        addr, log2(CPU_MANAGED_AXI4_BEAT_BYTES), part_len, data, strb_ptr);
    wait_write(cpu_managed_axi4);

    len -= (part_len + 1);
    addr += (part_len + 1) * CPU_MANAGED_AXI4_BEAT_BYTES;
    data += (part_len + 1) * CPU_MANAGED_AXI4_BEAT_BYTES;
    strb_ptr += (part_len + 1);
  }

  return size;
}

void simif_emul_t::load_mems(const char *fname) {
  slave[0]->load_mem(0, fname);
  // TODO: allow file to be split across slaves
}
