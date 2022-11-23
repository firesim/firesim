#ifndef __SIMIF_U250_H
#define __SIMIF_U250_H

#include "simif.h"    // from midas
#include "bridges/cpu_managed_stream.h"


class simif_u250_t: public virtual simif_t
{
  public:
    simif_u250_t(int argc, char** argv);
    virtual ~simif_u250_t();

    // Unused by u250 since initialization / deinitization is done in the constructor
    virtual void host_init(int argc, char** argv) { (void) argc; (void) argv; };
    virtual int host_finish() { return 0; };

    virtual void write(size_t addr, uint32_t data);
    virtual uint32_t read(size_t addr);
    virtual size_t pull(unsigned int stream_idx, void* dest, size_t num_bytes, size_t threshold_bytes);
    virtual size_t push(unsigned int stream_idx, void* src, size_t num_bytes, size_t threshold_bytes);
    uint32_t is_write_ready();
    void check_rc(int rc, char * infostr);
    void fpga_shutdown();
    void fpga_setup(int slot_id);
  private:
    char in_buf[CTRL_BEAT_BYTES];
    char out_buf[CTRL_BEAT_BYTES];

    std::vector<StreamToCPU> to_host_streams;
    std::vector<StreamFromCPU> from_host_streams;

    size_t pcis_write(size_t addr, char *data, size_t size);
    size_t pcis_read(size_t addr, char* data, size_t size);

    void * fpga_pci_bar_get_mem_at_offset(uint64_t offset);
    int fpga_pci_poke(uint64_t offset, uint32_t value);
    int fpga_pci_peek(uint64_t offset, uint32_t *value);

#ifdef SIMULATION_XSIM
    char * driver_to_xsim = "/tmp/driver_to_xsim";
    char * xsim_to_driver = "/tmp/xsim_to_driver";
    int driver_to_xsim_fd;
    int xsim_to_driver_fd;
#else
    int slot_id;
    int edma_write_fd;
    int edma_read_fd;
    void* bar0_base;
    uint64_t dma_offset;
    int engine_id;
#endif

};

#endif // __SIMIF_U250_H
