// See LICENSE for license details.

#include <cassert>
#include <stdexcept>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <iostream>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "bridges/cpu_managed_stream.h"
#include "bridges/fpga_managed_stream.h"
#include "core/config.h"
#include "core/simif.h"
#include "core/v80_slash_ddr_stream.h"
#include "core/v80_slash_mmio_stream.h"

#include <vrt/allocator/allocator.hpp>
#include <vrt/buffer.hpp>
#include <vrt/device.hpp>
#include <vrt/kernel.hpp>

/**
 * Simulation backend for the AMD Alveo V80 (Versal) through the SLASH stack
 * (https://github.com/Xilinx/SLASH). The kernel interface matches the Vitis
 * flow (AXI4-Lite control + one 64-bit AXI4 memory master backed by card
 * memory), but deployment uses VRT vrtbins instead of xclbins: registers are
 * accessed through vrt::Kernel::read/write and target memory lives in the
 * shell's DDR0 window, whose base must agree with FPGAMemBaseKey.
 */
class simif_xilinx_alveo_v80_slash_t final : public simif_t,
                                             public FPGAManagedStreamIO,
                                             public CPUManagedStreamIO {
public:
  simif_xilinx_alveo_v80_slash_t(const TargetConfig &config,
                                 const std::vector<std::string> &args);
  ~simif_xilinx_alveo_v80_slash_t() override;

  void write(size_t addr, uint32_t data) override;
  uint32_t read(size_t addr) override;

  // Capture loadmem writes in caller order and synchronize their spans on
  // flush rather than issuing a control-register transaction for every word.
  bool write_dram_bulk(size_t addr, const void *data, size_t bytes) override;
  void flush_dram_writes() override;
  FPGAManagedStreamIO &get_fpga_managed_stream_io() override { return *this; }

  CPUManagedStreamIO &get_cpu_managed_stream_io() override { return *this; }
  uint64_t get_beat_bytes() const override {
#ifdef V80_DDR_STREAM_METADATA
    return 8;
#else
    return 4;
#endif
  }
  size_t
  cpu_managed_axi4_write(size_t addr, const char *data, size_t size) override {
#ifdef V80_DDR_STREAM_METADATA
    if (!size)
      return 0;
    v80_slash_ddr_stream::validate(V80_DDR_STREAM_MIN_DDR, addr, size);
    if (!data)
      throw std::invalid_argument("DDR stream push requires source storage");
    ensure_dma_stream_staging(size);
    memcpy(dma_stream_host, data, size);
    sync_to_device(dma_stream_host, size);
    v80_slash_ddr_stream::transfer(*this, dma_stream_address, addr, size, true);
    return size;
#elif defined(V80_MMIO_STREAM_METADATA)
    return v80_slash_mmio_stream::push(*this, addr, data, size);
#else
    throw std::runtime_error("Image has no V80 CPU stream transport");
#endif
  }
  size_t cpu_managed_axi4_read(size_t addr, char *data, size_t size) override {
#ifdef V80_DDR_STREAM_METADATA
    if (!size)
      return 0;
    v80_slash_ddr_stream::validate(V80_DDR_STREAM_MIN_DDR, addr, size);
    if (!data)
      throw std::invalid_argument(
          "DDR stream pull requires destination storage");
    ensure_dma_stream_staging(size);
    v80_slash_ddr_stream::transfer(
        *this, dma_stream_address, addr, size, false);
    sync_from_device(dma_stream_host, size);
    memcpy(data, dma_stream_host, size);
    return size;
#elif defined(V80_MMIO_STREAM_METADATA)
    return v80_slash_mmio_stream::pull(*this, addr, data, size);
#else
    throw std::runtime_error("Image has no V80 CPU stream transport");
#endif
  }

private:
  uint32_t mmio_read(size_t addr) override { return read(addr); }

  void mmio_write(size_t addr, uint32_t value) override { write(addr, value); }

  FPGAManagedStreams::HostBuffer allocate_to_cpu_buffer(size_t bytes) override {
    char *host;
    auto address = allocate_stream_buffer(bytes, &host);
    return {host, address};
  }

  // FPGA-managed stream ring placement. Rings live in a
  // dedicated DDR0 window carved from the same allocator as the loadmem
  // bulk window (so the two never overlap), and are handed to the widget as
  // a design-relative offset: the SlashShim adds FPGAMemBase back onto the
  // design's AXI4 master addresses, turning the relative offset into a card
  // address. Host-side mirrors move spans with span-scoped QDMA syncs.
  uint64_t allocate_stream_buffer(size_t bytes, char **host_base);
  void sync_from_device(char *host, size_t bytes) override;
  void sync_to_device(char *host, size_t bytes) override;
  size_t stream_offset(char *host, size_t bytes) const;

  // Allocate or regrow the bulk-DRAM window to at least `need` bytes (no-op
  // when the current window already covers it). See the definition for the
  // aperture-base invariant this enforces.
  void ensure_dram_window(size_t need);
  void
  copy_dram_span(size_t addr, const uint8_t *data, size_t bytes, bool sync);

  std::string bdf;
  std::string vbin_file;
  // Base address the elaborated design emits for target-memory transactions.
  // Must match the FPGAMemBaseKey value baked into the bitstream. On the
  // SLASH shell the design's mem master (axi4_full_passthrough) decodes to
  // DDR0 at 0x0600_0000_0000 with a 32 GiB aperture (service_layer.tcl), and
  // the allocator's returned physical address is checked against that base.
  uint64_t dram_base = 0x60000000000ull;
  // Size of the DDR region to reserve for the target. The full target window
  // need not be allocated; only bridges that DMA into it require residency.
  uint64_t dram_alloc =
      0; // no DDR reservation unless +dram_alloc=<bytes> is given
  bool audit_dma = false;
  bool use_loaded_image = false;
#ifdef V80_DDR_STREAM_METADATA
  char *dma_stream_host = nullptr;
  uint64_t dma_stream_address = 0;
  void ensure_dma_stream_staging(size_t bytes) {
    if (bytes > v80_slash_ddr_stream::capacity)
      throw std::out_of_range("DDR stream DMA staging overflow");
    if (!dma_stream_host) {
      // Pending loadmem can require a larger window than the generated
      // minimum. Flush it before fixing the stream pool's placement.
      flush_dram_writes();
      ensure_dram_window(V80_DDR_STREAM_MIN_DDR);
      dma_stream_address = allocate_stream_buffer(
          v80_slash_ddr_stream::capacity, &dma_stream_host);
      if (dma_stream_address < V80_DDR_STREAM_MIN_DDR)
        throw std::runtime_error("DDR stream overlaps target memory");
    }
  }
#endif
  size_t control_write_fence = 0;
  static constexpr size_t control_alias = 0x1000;

  // vrt::Device is neither copyable nor movable, so hold it behind a pointer.
  std::unique_ptr<vrt::Device> device_handle;
  std::unique_ptr<vrt::Kernel> kernel_handle;

  // QDMA bulk window (flush_dram_writes). Held at the allocator level rather
  // than through vrt::Buffer: Buffer::sync is whole-window only, and the
  // SLASH vrt allocator rejects any request below its 4 KiB buddy minimum
  // instead of rounding up -- the first TSI loadmem chunks are smaller than
  // that, so raw span_extent requests would throw.
  std::unique_ptr<vrt::Block> dram_block;
  std::vector<std::unique_ptr<vrt::Block>> dram_extra_blocks;
  vrt::UntypedBuffer *dram_view = nullptr;
  uint8_t *dram_host = nullptr;

  // Retain widget-offset spans in capture order: overwrites may cover only
  // part of an earlier span. Keep flushed host spans for mirror reconstruction
  // when the target window is regrown before stream allocation.
  struct dram_span {
    size_t address;
    std::vector<uint8_t> bytes;
  };
  std::vector<dram_span> pending_dram_writes;
  std::vector<dram_span> flushed_dram_spans;
  size_t dram_aperture = 32ull * 1024 * 1024 * 1024;
  size_t span_extent = 0;   // max(addr + bytes) over all spans ever captured
  size_t dram_buf_size = 0; // current dram_buffer allocation size

  // FPGA-managed stream window (lazy, allocated on first ring placement;
  // never regrown -- a regrow would invalidate host bases already handed to
  // stream drivers, so the window is sized generously up front).
  std::unique_ptr<vrt::Block> stream_block;
  vrt::UntypedBuffer *stream_view = nullptr;
  uint8_t *stream_host = nullptr;
  size_t stream_buf_size = 0;
  size_t stream_bump = 0;
};

simif_xilinx_alveo_v80_slash_t::simif_xilinx_alveo_v80_slash_t(
    const TargetConfig &config, const std::vector<std::string> &args)
    : simif_t(config) {
  if (config.mem_num_channels > 1 ||
      (config.mem_num_channels == 1 && config.mem.addr_bits == 0))
    throw std::invalid_argument(
        "V80 SLASH supports zero or one nonempty DDR address space");
  if (config.mem_num_channels == 0)
    dram_aperture = 0;
  else if (config.mem.addr_bits < 35)
    dram_aperture = size_t(1) << config.mem.addr_bits;
#ifdef V80_DDR_STREAM_METADATA
  if (!dram_aperture)
    throw std::invalid_argument("DDR streams require a DDR address space");
#endif

  for (auto &arg : args) {
    if (arg.find("+bdf=") == 0) {
      bdf = arg.substr(5, std::string::npos);
    }
    if (arg.find("+binary_file=") == 0) {
      vbin_file = arg.substr(13, std::string::npos);
    }
    if (arg.find("+dram_base=") == 0) {
      dram_base =
          strtoull(arg.substr(11, std::string::npos).c_str(), nullptr, 0);
    }
    if (arg.find("+dram_alloc=") == 0) {
      dram_alloc =
          strtoull(arg.substr(12, std::string::npos).c_str(), nullptr, 0);
    }
    if (arg == "+v80-audit-dma")
      audit_dma = true;
    if (arg == "+v80-use-loaded-image")
      use_loaded_image = true;
    if (arg.find("+v80-control-write-fence=") == 0)
      control_write_fence = std::stoull(arg.substr(25), nullptr, 0);
  }

  // Reject reservations outside the generated design before opening the SDK.
  // Pure MMIO images have no DDR channel even when the shell has DDR memory.
  if (dram_alloc > dram_aperture)
    throw std::out_of_range("DRAM reservation exceeds the design address space");

  if (vbin_file == "") {
    fprintf(stderr,
            "No vrtbin file specified (+binary_file=<path-to-vbin>).\n");
    exit(1);
  }
  if (!use_loaded_image)
    throw std::invalid_argument("V80 SLASH requires a qualified preloaded "
                                "image (+v80-use-loaded-image)");

  fprintf(stdout, "DEBUG: BDF:%s VBIN:%s\n", bdf.c_str(), vbin_file.c_str());

  // Deployment programs and verifies the image before starting this driver.
  // Opening its vrtbin here must not invoke the SDK's programming path.
  // The PF2 BAR0 MSI-X table overlays 0x40..0xbf. The SLASH kernel has
  // a 12-bit AXI-Lite address port, so the next 4 KiB page aliases the
  // original register window without touching the PCIe interrupt table.
  if (config.ctrl.addr_bits != 12)
    throw std::runtime_error(
        "V80 control alias requires a 12-bit AXI-Lite port");
  // The kernel owns the first 64 bytes. Validate an optional fence before
  // opening the SDK device, which can activate services or access hardware.
  if (control_write_fence &&
      (control_write_fence < 0x40 || control_write_fence >= control_alias ||
       control_write_fence % sizeof(uint32_t)))
    throw std::runtime_error("Invalid generated control write fence address");
  device_handle = std::make_unique<vrt::Device>(bdf, vbin_file, false);

  // slashkit instance naming (firesim_0) differs from the kernel name
  // (firesim); accept either, preferring whatever the system map provides.
  for (const auto &kernel_name : {"firesim_0", "firesim"}) {
    try {
      kernel_handle =
          std::make_unique<vrt::Kernel>(device_handle->getKernel(kernel_name));
      fprintf(stdout, "DEBUG: selected kernel: %s\n", kernel_name);
      break;
    } catch (const std::exception &e) {
      fprintf(
          stderr, "Kernel %s not found in vrtbin: %s\n", kernel_name, e.what());
    }
  }
  if (!kernel_handle) {
    fprintf(stderr,
            "Could not find a 'firesim' kernel instance in %s.\n",
            vbin_file.c_str());
    exit(1);
  }
  if (control_write_fence) {
    if (kernel_handle->read(control_alias + control_write_fence) != 0x46697265)
      throw std::runtime_error(
          "Control write fence must be the original presence register");
  }

  // The original master widget validates the generated address map and reset.
  // Optionally reserve the target-DRAM window inside the shell's DDR region.
  // Only needed when the simulation expects host-visible device memory
  // (DMA-style buffers); MMIO-driven targets need no reservation.
  if (dram_alloc > 0) {
    // Use the same allocator block as loadmem and streams. A separate Buffer
    // would consume the base, making the first bulk allocation land elsewhere.
    ensure_dram_window(dram_alloc);
  }
}

simif_xilinx_alveo_v80_slash_t::~simif_xilinx_alveo_v80_slash_t() {
  if (stream_block && device_handle) {
    device_handle->getHandle()->getAllocator()->deallocate(
        std::move(stream_block));
  }
  if (dram_block && device_handle) {
    for (auto &block : dram_extra_blocks)
      device_handle->getHandle()->getAllocator()->deallocate(std::move(block));
    dram_extra_blocks.clear();
    device_handle->getHandle()->getAllocator()->deallocate(
        std::move(dram_block));
  }
  if (device_handle) {
    device_handle->cleanup();
  }
}

void simif_xilinx_alveo_v80_slash_t::write(size_t addr, uint32_t data) {
  if (addr < 0x40 || addr >= control_alias || addr % sizeof(uint32_t))
    throw std::out_of_range(
        "V80 control address must be an aligned word in [0x40, 0x1000)");
  // Preserve original loadmem ordering before any control operation can
  // release the target or observe memory. Consecutive load chunks still
  // coalesce.
  flush_dram_writes();
  kernel_handle->write(control_alias + addr, data);
  if (control_write_fence)
    (void)kernel_handle->read(control_alias + control_write_fence);
}

uint32_t simif_xilinx_alveo_v80_slash_t::read(size_t addr) {
  if (addr < 0x40 || addr >= control_alias || addr % sizeof(uint32_t))
    throw std::out_of_range(
        "V80 control address must be an aligned word in [0x40, 0x1000)");
  flush_dram_writes();
  return kernel_handle->read(control_alias + addr) & 0xFFFFFFFF;
}

bool simif_xilinx_alveo_v80_slash_t::write_dram_bulk(size_t addr,
                                                     const void *data,
                                                     size_t bytes) {
  if (bytes == 0)
    return true;
  // Subtraction checks both aperture overflow and size_t wrap before copying
  // source bytes or changing any pending-write state.
  if (addr > dram_aperture || bytes > dram_aperture - addr)
    throw std::out_of_range("V80 bulk write exceeds the design DDR aperture");
  if (stream_block && addr + bytes > dram_buf_size)
    throw std::out_of_range(
        "Reserve target DRAM before allocating stream rings (+dram_alloc)");
  if (!data)
    throw std::invalid_argument("V80 bulk write requires source storage");
  const auto *source = static_cast<const uint8_t *>(data);
  pending_dram_writes.push_back(
      {addr, std::vector<uint8_t>(source, source + bytes)});
  span_extent = std::max(span_extent, addr + bytes);
  return true;
}

// Allocate or regrow the bulk-DRAM window to at least `need` bytes (rounded
// up to 2 MiB: the SLASH vrt allocator throws on requests below its 4 KiB
// buddy minimum and buckets sizes by power-of-two, and early TSI loadmem
// chunks are far below that).
//
// The window base must match the address the design's memory master bakes in
// (FPGAMemBaseKey) -- the same invariant the +dram_alloc path checks at
// startup; a regrow is required to land at the same base or the driver aborts
// (previously flushed spans would otherwise be stranded). Regrow therefore
// drops the old window whole and relies on the device pool handing the freed
// superblock region back. The returned base is checked on every allocation.
// A failed reservation releases every new block and leaves captured writes
// queued. The caller can report the failure or retry without stale views.
//
// Streams call this before claiming their own window so the aperture base is
// already spoken for when the 64 MiB stream superblock is carved out of the
// next pool region -- otherwise the stream window would take the base and the
// first bulk allocation would land one superblock up, aborting here.
void simif_xilinx_alveo_v80_slash_t::ensure_dram_window(size_t need) {
  if (need > dram_aperture)
    throw std::out_of_range("V80 target reservation exceeds the DDR aperture");
  if (dram_block && need <= dram_buf_size)
    return;
  if (stream_block)
    throw std::runtime_error(
        "Reserve target DRAM before allocating stream rings (+dram_alloc)");
  auto *dev = device_handle->getHandle().get();
  if (dram_block) {
    for (auto &block : dram_extra_blocks)
      dev->getAllocator()->deallocate(std::move(block));
    dram_extra_blocks.clear();
    dev->getAllocator()->deallocate(std::move(dram_block));
    dram_view = nullptr;
    dram_host = nullptr;
    dram_buf_size = 0;
  }
  constexpr size_t window_gran = 2 * 1024 * 1024;
  need = (need + window_gran - 1) & ~(window_gran - 1);
  if (need == 0)
    need = window_gran;
  // vrtd limits each allocation to one 512 MiB region. Reserve consecutive
  // regions separately, retaining every block so target DRAM cannot overlap
  // later stream allocations. Host DMA spans are split at the same boundaries.
  constexpr size_t region_bytes = 512ULL * 1024 * 1024;
  auto first = dev->getAllocator()->allocate(dev->getVrtdDevice(),
                                             vrt::BufferAllocType::Ddr,
                                             vrt::BufferAllocDir::Bidirectional,
                                             std::min(need, region_bytes),
                                             vrt::HBMRegion::NON_HBM);
  if (!first)
    throw std::bad_alloc();
  std::vector<std::unique_ptr<vrt::Block>> extra;
  size_t reserved = 0;
  try {
    auto *view = first->getUntypedBuffer();
    if (view->getPhysAddr() != dram_base)
      throw std::runtime_error(
          "Target DRAM base differs from the generated design aperture");
    reserved = view->getSize();
    while (reserved < need) {
      // Add ownership before validation so every failure returns storage to
      // the SDK allocator, including a nonconsecutive physical allocation.
      extra.push_back(nullptr);
      extra.back() =
          dev->getAllocator()->allocate(dev->getVrtdDevice(),
                                        vrt::BufferAllocType::Ddr,
                                        vrt::BufferAllocDir::Bidirectional,
                                        std::min(need - reserved, region_bytes),
                                        vrt::HBMRegion::NON_HBM);
      if (!extra.back())
        throw std::bad_alloc();
      view = extra.back()->getUntypedBuffer();
      if (view->getPhysAddr() != dram_base + reserved)
        throw std::runtime_error(
            "Target DRAM regions must be physically consecutive");
      reserved += view->getSize();
    }
  } catch (...) {
    for (auto &block : extra)
      if (block)
        dev->getAllocator()->deallocate(std::move(block));
    dev->getAllocator()->deallocate(std::move(first));
    throw;
  }
  dram_block = std::move(first);
  dram_extra_blocks = std::move(extra);
  dram_view = dram_block->getUntypedBuffer();
  dram_host = static_cast<uint8_t *>(dram_view->data());
  dram_buf_size = reserved;
  // Initialize host mirrors only; untouched card bytes remain unchanged.
  memset(dram_host, 0, dram_view->getSize());
  for (auto &block : dram_extra_blocks) {
    auto *view = block->getUntypedBuffer();
    memset(view->data(), 0, view->getSize());
  }
  for (auto &span : flushed_dram_spans)
    copy_dram_span(span.address, span.bytes.data(), span.bytes.size(), false);
  fprintf(stdout,
          "DEBUG: bulk-DRAM window: base=0x%llx size=%zu KiB\n",
          (unsigned long long)dram_base,
          dram_buf_size >> 10);
}

void simif_xilinx_alveo_v80_slash_t::copy_dram_span(size_t addr,
                                                    const uint8_t *data,
                                                    size_t bytes,
                                                    bool sync) {
  while (bytes) {
    vrt::UntypedBuffer *view = dram_view;
    size_t base = 0;
    if (addr >= view->getSize()) {
      base = view->getSize();
      view = nullptr;
      for (auto &block : dram_extra_blocks) {
        auto *candidate = block->getUntypedBuffer();
        if (addr < base + candidate->getSize()) {
          view = candidate;
          break;
        }
        base += candidate->getSize();
      }
    }
    if (!view)
      throw std::out_of_range("Host DMA span exceeds reserved target DRAM");
    const size_t offset = addr - base;
    const size_t count =
        std::min(bytes, static_cast<size_t>(view->getSize() - offset));
    memcpy(static_cast<uint8_t *>(view->data()) + offset, data, count);
    if (sync) {
      view->syncToDevice(offset, count);
      if (audit_dma) {
        view->syncToHost(offset, count);
        if (memcmp(static_cast<uint8_t *>(view->data()) + offset, data, count))
          throw std::runtime_error(
              "V80 program DMA readback differs from captured ELF span");
        fprintf(
            stdout, "DMA-READBACK-PASS offset=0x%zx bytes=%zu\n", addr, count);
      }
    }
    addr += count;
    data += count;
    bytes -= count;
  }
}

void simif_xilinx_alveo_v80_slash_t::flush_dram_writes() {
  if (pending_dram_writes.empty())
    return;

  // One DDR window backing every captured span, allocated on first use and
  // regrown by whole-window reallocation when a later flush exceeds it.
  ensure_dram_window(span_extent);

  for (auto &span : pending_dram_writes) {
    // Span-granular sync: only the captured bytes cross PCIe. (vrt::Buffer::
    // sync would move the whole window on every flush.)
    copy_dram_span(span.address, span.bytes.data(), span.bytes.size(), true);
  }

  for (auto &span : pending_dram_writes)
    flushed_dram_spans.push_back(std::move(span));
  pending_dram_writes.clear();
}

uint64_t
simif_xilinx_alveo_v80_slash_t::allocate_stream_buffer(size_t bytes,
                                                       char **host_base) {
  if (!host_base || !bytes || (bytes & (bytes - 1)) || bytes > (64ull << 20))
    throw std::invalid_argument(
        "V80 stream buffer must have a bounded power-of-two size");
  // Rings must sit at least 4 KiB aligned so the engine's page-boundary
  // accounting (relative offsets) matches real AXI4 burst boundaries; ring
  // sizes are powers of two >= 4 KiB, so aligning to the ring size gives
  // both. Sizes are rounded up to a 2 MiB granularity in step with the
  // loadmem window's allocator constraints.
  if (dram_aperture < (64ull << 20))
    throw std::out_of_range(
        "Design DDR address space cannot contain the stream pool");
  if (!stream_block) {
    // The bulk-DRAM window must own the aperture base the design bakes in;
    // claim it before the 64 MiB stream allocation consumes the first pool
    // region (see ensure_dram_window for the invariant).
    ensure_dram_window(2 * 1024 * 1024);
    constexpr size_t window_gran = 64 * 1024 * 1024;
    const size_t aperture = dram_aperture;
    auto *dev = device_handle->getHandle().get();
    auto block =
        dev->getAllocator()->allocate(dev->getVrtdDevice(),
                                      vrt::BufferAllocType::Ddr,
                                      vrt::BufferAllocDir::Bidirectional,
                                      window_gran,
                                      vrt::HBMRegion::NON_HBM);
    if (!block)
      throw std::bad_alloc();
    auto *view = block->getUntypedBuffer();
    const uint64_t phys = view->getPhysAddr();
    if (phys < dram_base || phys - dram_base > aperture - window_gran) {
      dev->getAllocator()->deallocate(std::move(block));
      throw std::runtime_error(
          "Stream allocation falls outside the design DDR aperture");
    }
    stream_block = std::move(block);
    stream_view = view;
    stream_buf_size = window_gran;
    stream_host = static_cast<uint8_t *>(stream_view->data());
    fprintf(stdout,
            "DEBUG: stream window: base=0x%llx size=%zu KiB\n",
            (unsigned long long)phys,
            stream_buf_size >> 10);
  }
  size_t align = std::max(bytes, (size_t)4096);
  size_t off = (stream_bump + align - 1) & ~(align - 1);
  if (off + bytes > stream_buf_size) {
    throw std::runtime_error(
        "simif_xilinx_alveo_v80_slash: stream window exhausted -- enlarge "
        "window_gran in allocate_stream_buffer.");
  }
  stream_bump = off + bytes;
  *host_base = reinterpret_cast<char *>(stream_host + off);
  return stream_view->getPhysAddr() - dram_base + off;
}

void simif_xilinx_alveo_v80_slash_t::sync_from_device(char *host,
                                                      size_t bytes) {
  if (!bytes)
    return;
  const size_t off = stream_offset(host, bytes);
  stream_view->syncToHost(off, bytes);
}

void simif_xilinx_alveo_v80_slash_t::sync_to_device(char *host, size_t bytes) {
  if (!bytes)
    return;
  const size_t off = stream_offset(host, bytes);
  stream_view->syncToDevice(off, bytes);
}

size_t simif_xilinx_alveo_v80_slash_t::stream_offset(char *host,
                                                     size_t bytes) const {
  const auto address = reinterpret_cast<uintptr_t>(host);
  const auto base = reinterpret_cast<uintptr_t>(stream_host);
  if (!stream_view || !host || address < base ||
      address - base > stream_buf_size ||
      bytes > stream_buf_size - (address - base))
    throw std::out_of_range(
        "V80 stream sync exceeds the allocated host window");
  return address - base;
}

std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  return std::make_unique<simif_xilinx_alveo_v80_slash_t>(config, args);
}
