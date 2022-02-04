#ifndef __SIMIF_VITIS_H
#define __SIMIF_VITIS_H

#include "simif.h"

#include "experimental/xrt_kernel.h"
#include "experimental/xrt_device.h"
#include "experimental/xrt_ip.h"

class simif_vitis_t: public virtual simif_t
{
  public:
    simif_vitis_t(int argc, char** argv);
    virtual ~simif_vitis_t();
    virtual void write(size_t addr, uint32_t data);
    virtual uint32_t read(size_t addr);
    virtual ssize_t pull(size_t addr, char* data, size_t size);
    virtual ssize_t push(size_t addr, char* data, size_t size);
    uint32_t is_write_ready();

    // Unused by Vitis since initialization / deinitization is done in the constructor
    virtual void host_init(int argc, char** argv) {};
    virtual int host_finish() { return 0; };
  private:
    int device_index;
    std::string binary_file;
    xrt::device device_handle;
    xrt::uuid uuid;
    xrt::ip kernel_handle;
    xrt::run run_handle;
};

#endif // __SIMIF_VITIS_H
