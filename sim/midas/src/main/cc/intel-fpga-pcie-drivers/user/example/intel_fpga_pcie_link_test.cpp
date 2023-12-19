// (C) 2017-2018 Intel Corporation.
//
// Intel, the Intel logo, Intel, MegaCore, NIOS II, Quartus and TalkBack words
// and logos are trademarks of Intel Corporation or its subsidiaries in the
// U.S. and/or other countries. Other marks and brands may be claimed as the
// property of others. See Trademarks on intel.com for full list of Intel
// trademarks or the Trademarks & Brands Names Database (if Intel) or see
// www.intel.com/legal (if Altera). Your use of Intel Corporation's design
// tools, logic functions and other software and tools, and its AMPP partner
// logic functions, and any output files any of the foregoing (including
// device programming or simulation files), and any associated documentation
// or information are expressly subject to the terms and conditions of the
// Altera Program License Subscription Agreement, Intel MegaCore Function
// License Agreement, or other applicable license agreement, including,
// without limitation, that your use is for the sole purpose of programming
// logic devices manufactured by Intel and sold by Intel or its authorized
// distributors. Please refer to the applicable agreement for further details.

#include <iostream>
#include <iomanip>
#include <cstdlib>
#include <limits>
#include <time.h>
#include <termios.h>
#include <ctime>
#include <system_error>
#include <cerrno>
#include <stdexcept>

#include "intel_fpga_pcie_api.hpp"
#include "intel_fpga_pcie_link_test.hpp"

using namespace std;

static unsigned int welcome_options(void);
static uint16_t sel_dev_menu(void);
static int sel_bar_menu(void);
static unsigned int access_options(void);
static bool do_pio_test(intel_fpga_pcie_dev *dev);
static void do_wr(intel_fpga_pcie_dev *dev);
static void do_rd(intel_fpga_pcie_dev *dev);
static void do_cfg_wr(intel_fpga_pcie_dev *dev);
static void do_cfg_rd(intel_fpga_pcie_dev *dev);
static void do_bar_sel(intel_fpga_pcie_dev *dev);
static void do_dev_sel(intel_fpga_pcie_dev *dev);
static void do_sriov_en(intel_fpga_pcie_dev *dev);
static void do_sriov_test(intel_fpga_pcie_dev *dev);
static void dma_mode(intel_fpga_pcie_dev *dev);
static unsigned int dma_options(void);
static void dma_show_config(bool run_rd, bool run_wr, bool run_simul,
                            unsigned int num_dw, unsigned int num_desc);
static unsigned int dma_set_num_dw(void);
static unsigned int dma_set_num_desc(void);
static void dma_run(intel_fpga_pcie_dev *dev, bool run_rd, bool run_wr,
                    bool run_simul, unsigned int num_dw,
                    unsigned int num_desc);
static void dma_show_perf(bool run_rd, bool run_wr, bool run_simul,
                          unsigned int num_dw, unsigned int num_desc,
                          unsigned int cur_run, double (&rd_perf)[2],
                          double (&wr_perf)[2], double (&simul_perf)[2]);
static int kbhit(void);
static void init_rp_mem(intel_fpga_pcie_dev *dev, uint32_t *addr,
                        unsigned int num_dw, unsigned int seed, int fill_mode);
static int init_ep_mem(intel_fpga_pcie_dev *dev, uint32_t *addr,
                       unsigned int num_dw, unsigned int seed, int fill_mode);
static int rp_ep_compare(intel_fpga_pcie_dev *dev, uint32_t *rp_addr,
                         uint32_t *ep_addr, unsigned int num_dw);
static uint32_t pci_find_capability(intel_fpga_pcie_dev *dev, int cap);
static uint32_t pci_find_ext_capability(intel_fpga_pcie_dev *dev, int cap);


int main(void)
{
    intel_fpga_pcie_dev *dev;
    unsigned int opt;
    uint16_t bdf = 0;
    int bar = -1;
    std::ios_base::fmtflags f(cout.flags()); // Record initial flags
    
    try {

    opt = welcome_options();

    if (opt == WELCOME_OPT_MANUAL) {
        bdf = sel_dev_menu();
        bar = sel_bar_menu();
    }

    try {
        dev = new intel_fpga_pcie_dev(bdf,bar);
    } catch (const std::exception& ex) {
        cout << "Invalid BDF or BAR!" << endl;
        throw;
    }
    cout << hex << showbase;
    cout << "Opened a handle to BAR " << dev->get_bar();
    cout << " of a device with BDF " << dev->get_dev() << endl;

    do {
        opt = access_options();
        switch (opt) {
        case ACCESS_OPT_TEST:
            do_pio_test(dev);
            break;
        case ACCESS_OPT_WR:
            do_wr(dev);
            break;
        case ACCESS_OPT_RD:
            do_rd(dev);
            break;
        case ACCESS_OPT_CFG_WR:
            do_cfg_wr(dev);
            break;
        case ACCESS_OPT_CFG_RD:
            do_cfg_rd(dev);
            break;
        case ACCESS_OPT_SEL_BAR:
            do_bar_sel(dev);
            break;
        case ACCESS_OPT_SEL_DEV:
            do_dev_sel(dev);
            break;
        case ACCESS_OPT_SRIOV_EN:
            do_sriov_en(dev);
            break;
        case ACCESS_OPT_SRIOV_TEST:
            do_sriov_test(dev);
            break;
        case ACCESS_OPT_DMA:
            dma_mode(dev);
            break;
        case ACCESS_OPT_QUIT:
            // Fall-through
        default:
            break;
        }
    } while (opt != ACCESS_OPT_QUIT);

    } catch (std::exception& ex) {
        cout.flags(f); // Restore initial flags
        cout << ex.what() << endl;
        return -1;
    }
    
    cout.flags(f); // Restore initial flags
}

static unsigned int welcome_options(void)
{
    unsigned int option;
    bool cin_fail;

    cout << dec;
    cin >> dec;

    do {
        cout << "\n" << SEL_MENU_DELIMS << "\n";
        cout << "Intel FPGA PCIe Link Test\n";
        cout << "Version "<< version_major << "." << version_minor << "\n";
        cout << WELCOME_OPT_AUTO   << ": Automatically select a device\n";
        cout << WELCOME_OPT_MANUAL << ": Manually select a device\n";
        cout << SEL_MENU_DELIMS << endl;

        cout << "> " << flush;
        cin >> option;
        cin_fail = cin.fail();
        cin.clear();
        cin.ignore(numeric_limits<streamsize>::max(), '\n');

        if (cin_fail || (option > WELCOME_OPT_MAXNR)) {
            cout << "Invalid option" << endl;
        }
    } while (cin_fail || (option > WELCOME_OPT_MAXNR));

    return option;
}

static uint16_t sel_dev_menu(void)
{
    unsigned int bus, dev, function;
    uint16_t bdf;
    std::ios_base::fmtflags f(cout.flags()); // Record initial flags

    cout << hex;
    cin >> hex;

    cout << "Enter bus number, in hex:\n";
    cout << "> " << flush;
    cin >> bus;

    cout << "Enter device number, in hex:\n";
    cout << "> " << flush;
    cin >> dev;

    cout << "Enter function number, in hex:\n";
    cout << "> " << flush;
    cin >> function;
    cin.clear();
    cin.ignore(numeric_limits<streamsize>::max(), '\n');

    bus &= 0xFF;
    dev &= 0x1F;
    function &= 0x7;
    bdf  = bus << 8;
    bdf |= dev << 3;
    bdf |= function;

    cout << "BDF is " << showbase << bdf << "\n";
    cout << "B:D.F, in hex, is " << noshowbase << bus << ":" << dev << "." << function << endl;
    cout.flags(f); // Restore initial flags
    return bdf;
}

static int sel_bar_menu(void)
{
    int bar;
    cout << "Enter BAR number (-1 for none):\n";
    cout << "> " << flush;
    cin >> dec >> bar;
    cin.clear();
    cin.ignore(numeric_limits<streamsize>::max(), '\n');

    return bar;
}

static unsigned int access_options(void)
{
    unsigned int option;
    bool cin_fail;
    std::ios_base::fmtflags f(cout.flags()); // Record initial flags

    cout << dec << showbase << setfill(' ') << internal;
    cin >> dec;

    do {
        cout << "\n" << SEL_MENU_DELIMS << "\n";
        cout << setw(2) << ACCESS_OPT_TEST       << ": Link test - 100 writes and reads\n";
        cout << setw(2) << ACCESS_OPT_WR         << ": Write memory space\n";
        cout << setw(2) << ACCESS_OPT_RD         << ": Read memory space\n";
        cout << setw(2) << ACCESS_OPT_CFG_WR     << ": Write configuration space\n";
        cout << setw(2) << ACCESS_OPT_CFG_RD     << ": Read configuration space\n";
        cout << setw(2) << ACCESS_OPT_SEL_BAR    << ": Change BAR for PIO\n";
        cout << setw(2) << ACCESS_OPT_SEL_DEV    << ": Change device\n";
        cout << setw(2) << ACCESS_OPT_SRIOV_EN   << ": Enable SRIOV\n";
        cout << setw(2) << ACCESS_OPT_SRIOV_TEST << ": Do a link test for every enabled virtual function\n  "
                                                 << "  belonging to the current device\n";
        cout << setw(2) << ACCESS_OPT_DMA        << ": Perform DMA\n";
        cout << setw(2) << ACCESS_OPT_QUIT       << ": Quit program\n";
        cout << SEL_MENU_DELIMS << endl;
        cout            << "> " << flush;
        cin >> option;
        cin_fail = cin.fail();
        cin.clear();
        cin.ignore(numeric_limits<streamsize>::max(), '\n');

        if (cin_fail || (option > ACCESS_OPT_MAXNR)) {
            cout << "Invalid option" << endl;
        }
    } while (cin_fail || (option > ACCESS_OPT_MAXNR));

    cout.flags(f); // Restore initial flags
    return option;
}

static bool do_pio_test(intel_fpga_pcie_dev *dev)
{
    int i;
    unsigned int seed;
    uint32_t temp_r, temp_w;
    char *addr = NULL;
    int wr_err_cnt = 0;
    int rd_err_cnt = 0;
    int mismatch_cnt = 0;
    int result;
    std::ios_base::fmtflags f(cout.flags()); // Record initial flags

    cout << noshowbase;

    // Initialize to random seed
    seed = time(NULL);
    srand(seed);

    cout << "Doing 100 writes and 100 reads.." << endl;

    for (i = 0; i < 100; ++i) {
        temp_w = rand();
        result = dev->write32(addr + 4*i, temp_w);
        if (result == 0) {
            ++wr_err_cnt;
        }
    }

    // Reinitialize to same seed
    srand(seed);

    for (i = 0; i < 100; ++i) {
        result = dev->read32(addr + 4*i, &temp_r);
        if (result == 0) {
            ++rd_err_cnt;
        }

        temp_w = rand();
        if (temp_r != temp_w) {
            cout << "At dword 0x" << hex << i << "\n";
            cout << "Wrote 0x" << setfill('0') << setw(8) << temp_w;
            cout << "\n";
            cout << "Read  0x" << setfill('0') << setw(8) << temp_r;
            cout << endl;
            ++mismatch_cnt;
        }
    }

    cout << dec << setfill(' ');
    cout << "Number of write errors:     " << setw(3) << wr_err_cnt << "\n";
    cout << "Number of read errors:      " << setw(3) << rd_err_cnt << "\n";
    cout << "Number of dword mismatches: " << setw(3) << mismatch_cnt << endl;

    cout.flags(f); // Restore initial flags
    if ((wr_err_cnt > 0) || (rd_err_cnt > 0) || (mismatch_cnt > 0)) {
        return false;
    } else {
        return true;
    }
}

static void do_wr(intel_fpga_pcie_dev *dev)
{
    uint64_t addr;
    uint32_t write_data;
    int result;
    std::ios_base::fmtflags f(cout.flags()); // Record initial flags

    cout << hex << showbase << setfill(' ') << internal;
    cin >> hex;

    cout << "Enter address to write, in hex:\n";
    cout << "> " << flush;
    cin >> addr;

    cout << "Enter 32-bit data to write, in hex:\n";
    cout << "> " << flush;
    cin >> write_data;
    cin.clear();
    cin.ignore(numeric_limits<streamsize>::max(), '\n');

    cout << "Writing " << write_data << " at ";
    cout << "BDF " << dev->get_dev();
    cout << " BAR " << dev->get_bar() << " offset " << addr;
    cout << ".." << endl;

    result = dev->write32(reinterpret_cast<void *>(addr), write_data);
    if (result == 1) {
        cout << "Wrote successfully!" << endl;
    } else {
        cout << "Write failed!" << endl;
    }
    cout.flags(f); // Restore initial flags
}

static void do_rd(intel_fpga_pcie_dev *dev)
{
    uint64_t addr;
    uint32_t read_data;
    int result;
    std::ios_base::fmtflags f(cout.flags()); // Record initial flags

    cout << hex << showbase << setfill(' ') << internal;
    cin >> hex;

    cout << "Enter address to read, in hex:\n";
    cout << "> " << flush;
    cin >> addr;
    cin.clear();
    cin.ignore(numeric_limits<streamsize>::max(), '\n');

    cout << "Reading from BDF " << dev->get_dev();
    cout << " BAR " << dev->get_bar() << " offset " << addr;
    cout << ".." << endl;

    result = dev->read32(reinterpret_cast<void *>(addr), &read_data);
    if (result == 1) {
        cout << "Read " << read_data << endl;
    } else {
        cout << "Read failed!" << endl;
    }
    cout.flags(f); // Restore initial flags
}

static void do_cfg_wr(intel_fpga_pcie_dev *dev)
{
    uint64_t addr;
    uint32_t write_data;
    int result;
    std::ios_base::fmtflags f(cout.flags()); // Record initial flags

    cout << hex << showbase << setfill(' ') << internal;
    cin >> hex;

    cout << "Enter address to write, in hex:\n";
    cout << "> " << flush;
    cin >> addr;

    cout << "Enter 32-bit data to write, in hex:\n";
    cout << "> " << flush;
    cin >> write_data;
    cin.clear();
    cin.ignore(numeric_limits<streamsize>::max(), '\n');

    cout << "Writing " << write_data << " at ";
    cout << "BDF " << dev->get_dev();
    cout << " config space offset " << addr << ".." << endl;

    result = dev->cfg_write32(reinterpret_cast<void *>(addr), write_data);
    if (result == 1) {
        cout << "Wrote successfully!" << endl;
    } else {
        cout << "Write failed!" << endl;
    }
    cout.flags(f); // Restore initial flags
}

static void do_cfg_rd(intel_fpga_pcie_dev *dev)
{
    uint64_t addr;
    uint32_t read_data;
    int result;
    std::ios_base::fmtflags f(cout.flags()); // Record initial flags

    cout << hex << showbase << setfill(' ') << internal;
    cin >> hex;

    cout << "Enter address to read, in hex:\n";
    cout << "> " << flush;
    cin >> addr;
    cin.clear();
    cin.ignore(numeric_limits<streamsize>::max(), '\n');

    cout << "Reading from BDF " << dev->get_dev();
    cout << " config space offset " << addr << ".." << endl;

    result = dev->cfg_read32(reinterpret_cast<void *>(addr), &read_data);
    if (result == 1) {
        cout << "Read " << read_data << endl;
    } else {
        cout << "Read failed!" << endl;
    }
    cout.flags(f); // Restore initial flags
}

static void do_bar_sel(intel_fpga_pcie_dev *dev)
{
    int bar;
    int result;

    cout << "Changing BAR..." << endl;
    bar = sel_bar_menu();
    result = dev->sel_bar(bar);

    if (result == 1) {
        cout << "Successfully changed BAR!" << endl;
    } else {
        cout << "Could not change BAR!" << endl;
    }
}

static void do_dev_sel(intel_fpga_pcie_dev *dev)
{
    uint16_t bdf;
    int result;

    cout << "Changing device..." << endl;
    bdf = sel_dev_menu();
    result = dev->sel_dev(bdf);

    if (result == 1) {
        cout << "Successfully changed device!" << endl;
    } else {
        cout << "Could not change device!" << endl;
    }
}

static void do_sriov_en(intel_fpga_pcie_dev *dev)
{
    unsigned int numvfs = 1;
    int result;

    cout << dec << noshowbase << setfill(' ');
    cin >> dec;

    cout << "Enter the number of VFs to enable for the current device:\n";
    cout << "> " << flush;
    cin >> numvfs;
    cin.clear();
    cin.ignore(numeric_limits<streamsize>::max(), '\n');

    result = dev->set_sriov_numvfs(numvfs);

    if (result != 1) {
        // This is not an expected result.
        cout << "Failed to enable " << numvfs << " VFs." << endl;
    } else {
        cout << "Enabled " << numvfs << " VFs.\n";
        cout << "Type 'lspci -d 1172:' in a new terminal to "
                "determine newly enabled devices' BDFs." << endl;
    }
}

static void do_sriov_test(intel_fpga_pcie_dev *dev)
{
    int result;
    int test_err_cnt = 0;
    int test_run_cnt = 0;
    uint32_t sriov_cap_offset, stride_offset;
    uint16_t numvfs, stride, offset, vf_bdf;
    std::ios_base::fmtflags f(cout.flags()); // Record initial flags

    // Determine numvfs, stride, offset.
    sriov_cap_offset = pci_find_ext_capability(dev, 0x0010);
    if (sriov_cap_offset == 0) {
        cout << "No SRIOV capability found." << endl;
        return;
    }

    result = dev->cfg_read16(reinterpret_cast<void *>(sriov_cap_offset + 0x10), &numvfs);
    if (result != 1) {
        cout << "Could not read NumVFs!" << endl;
        return;
    }
    result = dev->cfg_read32(reinterpret_cast<void *>(sriov_cap_offset + 0x14), &stride_offset);
    if (result != 1) {
        cout << "Could not read VF stride and first VF offset!" << endl;
        return;
    }
    stride = stride_offset >> 16;
    offset = stride_offset & 0xFFFF;
    test_run_cnt = numvfs;

    // Find the first VF of this PF and loop through all VFs.
    vf_bdf = dev->get_dev() + offset;
    intel_fpga_pcie_dev *vf_dev = new intel_fpga_pcie_dev();
    while (numvfs) {
        cout << hex << showbase << setfill(' ') << internal;
        result = vf_dev->sel_dev(vf_bdf);
        if (result == 1) {
            cout << "Testing VF with BDF " << vf_bdf << "..." << endl;
            if (!do_pio_test(vf_dev)) {
                ++test_err_cnt;
                cout << "Test failed for VF with BDF " << vf_bdf << endl;
            }
        } else {
            cout << "Could not access VF with BDF " << vf_bdf << endl;
            ++test_err_cnt;
        }

        --numvfs;
        vf_bdf += stride;
    }

    delete vf_dev;

    cout << dec << noshowbase;
    cout << "Test failed for " << test_err_cnt << " VFs out of " << test_run_cnt << " VFs" << endl;
    cout.flags(f); // Restore initial flags
}

static void dma_mode(intel_fpga_pcie_dev *dev)
{
    int result;
    unsigned int opt;
    bool run_rd = true;
    bool run_wr = true;
    bool run_simul = true;
    unsigned int num_dw = 2048;
    unsigned int num_desc = 128;

    result = dev->use_cmd(true);
    if (result == 0) {
        cout << "Could not switch to CMD use mode!" << endl;
        return;
    }

    do {
        dma_show_config(run_rd, run_wr, run_simul, num_dw, num_desc);
        opt = dma_options();
        switch (opt) {
        case DMA_OPT_RUN:
            dma_run(dev, run_rd, run_wr, run_simul, num_dw, num_desc);
            break;
        case DMA_OPT_TOGGLE_RD:
            run_rd = !run_rd;
            break;
        case DMA_OPT_TOGGLE_WR:
            run_wr = !run_wr;
            break;
        case DMA_OPT_TOGGLE_SIMUL:
            run_simul = !run_simul;
            break;
        case DMA_OPT_MOD_NUM_DWORDS:
            num_dw = dma_set_num_dw();
            break;
        case DMA_OPT_MOD_NUM_DESCS:
            num_desc = dma_set_num_desc();
            break;
        case DMA_OPT_QUIT:
            // Fall-through
        default:
            break;
        }
    } while (opt != DMA_OPT_QUIT);

    result = dev->use_cmd(false);
    if (result == 0) {
        cout << "Could not switch back from CMD use mode!" << endl;
        return;
    }
}

static unsigned int dma_options(void)
{
    unsigned int option;
    bool cin_fail;
    std::ios_base::fmtflags f(cout.flags()); // Record initial flags

    cout << dec << showbase << setfill(' ') << internal;
    cin >> dec;

    do {
        cout << "\n" << SEL_MENU_DELIMS << "\n";
        cout << setw(2) << DMA_OPT_RUN            << ": Run DMA\n";
        cout << setw(2) << DMA_OPT_TOGGLE_RD      << ": Toggle read DMA\n";
        cout << setw(2) << DMA_OPT_TOGGLE_WR      << ": Toggle write DMA\n";
        cout << setw(2) << DMA_OPT_TOGGLE_SIMUL   << ": Toggle simultaneous DMA\n";
        cout << setw(2) << DMA_OPT_MOD_NUM_DWORDS << ": Set the number of dwords per descriptor\n";
        cout << setw(2) << DMA_OPT_MOD_NUM_DESCS  << ": Set the number of descriptors per DMA\n";
        cout << setw(2) << DMA_OPT_QUIT           << ": Return to main menu\n";
        cout << SEL_MENU_DELIMS << endl;
        cout            << "> " << flush;
        cin >> option;
        cin_fail = cin.fail();
        cin.clear();
        cin.ignore(numeric_limits<streamsize>::max(), '\n');

        if (cin_fail || (option > DMA_OPT_MAXNR)) {
            cout << "Invalid option" << endl;
        }
    } while (cin_fail || (option > DMA_OPT_MAXNR));

    cout.flags(f); // Restore initial flags
    return option;
}

static void dma_show_config(bool run_rd, bool run_wr, bool run_simul,
                            unsigned int num_dw, unsigned int num_desc)
{
    std::ios_base::fmtflags f(cout.flags()); // Record initial flags
    cout << dec << noshowbase;
    cout << "\n" << SEL_MENU_DELIMS << "\n";
    cout << "Current DMA configurations" << endl;
    cout << "    Run Read  (card->system)  ? " << run_rd << endl;
    cout << "    Run Write (system->card)  ? " << run_wr << endl;
    cout << "    Run Simultaneous          ? " << run_simul << endl;
    cout << "    Number of dwords/desc     : " << num_dw << endl;
    cout << "    Number of descriptors     : " << num_desc << endl;
    cout << "    Total length of transfer  : " << (num_dw*num_desc*4/1024.0) << " KiB";
    cout.flags(f); // Restore initial flags
}

static unsigned int dma_set_num_dw(void)
{
    unsigned int num_dw;
    cout << "Enter the number of dwords to transfer per descriptor:\n";
    cout << "> " << flush;
    cin >> num_dw;
    cin.clear();
    cin.ignore(numeric_limits<streamsize>::max(), '\n');

    if (num_dw > (1*1024*1024/4)) {
        num_dw = 1*1024*1024/4;
        cout << "Reducing the number of dwords per descriptor to "
                "256*1024 == 1MB." << endl;
    }
    return num_dw;
}

static unsigned int dma_set_num_desc(void)
{
    unsigned int num_desc;
    cout << "Enter the number of descriptors to use per DMA:\n";
    cout << "> " << flush;
    cin >> num_desc;
    cin.clear();
    cin.ignore(numeric_limits<streamsize>::max(), '\n');

    if (num_desc > 128) {
        num_desc = 128;
        cout << "Reducing the number of descriptors used to 128." << endl;
    }

    return num_desc;
}

static void dma_run(intel_fpga_pcie_dev *dev, bool run_rd, bool run_wr,
                    bool run_simul, unsigned int num_dw,
                    unsigned int num_desc)
{
    int result;
    void *mmap_addr;
    uint32_t *kdata;
    unsigned int seed, i, cur_run;
    unsigned int rd_period   =std::numeric_limits<unsigned int>::max();
    unsigned int wr_period   =std::numeric_limits<unsigned int>::max();
    unsigned int simul_period=std::numeric_limits<unsigned int>::max();
    int rd_err_cnt, wr_err_cnt, simul_err_cnt;
    double rd_perf[2], wr_perf[2], simul_perf[2];
    unsigned long long payload_bytes;
    unsigned long long cumul_rd_time, cumul_wr_time, cumul_simul_time;

    unsigned int num_runs;
    cout << "Enter the number of DMA operations to initiate; enter 0 for "
            "infinite loop:\n";
    cout << "> " << flush;
    cin >> num_runs;
    cin.clear();
    cin.ignore(numeric_limits<streamsize>::max(), '\n');

    // Obtain kernel memory.
    result = dev->set_kmem_size(num_dw*4);
    if (result != 1) {
        cout << "Could not get kernel memory!" << endl;
        return;
    }
    mmap_addr = dev->kmem_mmap(num_dw*4, 0);
    if (mmap_addr == MAP_FAILED) {
        cout << "Could not get mmap kernel memory!" << endl;
        return;
    }
    kdata = reinterpret_cast<uint32_t *>(mmap_addr);

    payload_bytes = (unsigned long long) num_dw*num_desc*4;
    cumul_rd_time = 0;
    cumul_wr_time = 0; 
    cumul_simul_time = 0;

    for (cur_run=1; cur_run<=num_runs || num_runs==0; ++cur_run) {
        // Initialize to random seed;
        seed = time(NULL);

        if (run_rd) {
            // Initialize EP source memory
            init_ep_mem(dev, 0, num_dw, seed, FILL_INCR);

            // Clear RP destination memory;
            init_rp_mem(dev, kdata, num_dw, seed, FILL_ZERO);

            for (i=0; i<num_desc; ++i) {
                result = dev->dma_queue_read(0, num_dw*4, 0);
                if (result == 0) {
                    cout << "Could not queue DMA read! Aborting DMA.." << endl;
                    break;
                }
            }
            result = dev->dma_send_read();

            if (result != 0) {
                rd_period = dev->get_ktimer();
                cumul_rd_time += rd_period;
                rd_err_cnt = rp_ep_compare(dev, kdata, 0, num_dw);
                if (rd_err_cnt) {
                    cout << "Read DMA encountered " << rd_err_cnt << " errors." << endl;
                    result = 0;
                }
            }
        }

        if (run_wr) {
            // Initialize RP source memory
            init_rp_mem(dev, kdata, num_dw, seed, FILL_INCR);

            // Clear EP destination memory;
            init_ep_mem(dev, 0, num_dw, seed, FILL_ZERO);

            for (i=0; i<num_desc; ++i) {
                result = dev->dma_queue_write(0, num_dw*4, 0);
                if (result == 0) {
                    cout << "Could not queue DMA write! Aborting DMA.." << endl;
                    break;
                }
            }
            result = dev->dma_send_write();

            if (result != 0) {
                wr_period = dev->get_ktimer();
                cumul_wr_time += wr_period;
                wr_err_cnt = rp_ep_compare(dev, kdata, 0, num_dw);
                if (wr_err_cnt) {
                    cout << "Write DMA encountered " << wr_err_cnt << " errors." << endl;
                    result = 0;
                }
            }
        }

        if (run_simul) {
            // Initialize for read
            init_ep_mem(dev, 0, num_dw/2, seed, FILL_INCR);
            init_rp_mem(dev, kdata, num_dw/2, seed, FILL_ZERO);

            // Initialize for write
            init_rp_mem(dev, kdata+num_dw/2, num_dw/2, seed, FILL_INCR);
            init_ep_mem(dev, reinterpret_cast<uint32_t *>(num_dw*4/2),
                        num_dw/2, seed, FILL_ZERO);

            for (i=0; i<num_desc; ++i) {
                result = dev->dma_queue_read(0, num_dw*2, 0);
                if (result == 0) {
                    cout << "Could not queue DMA read! Aborting DMA.." << endl;
                    break;
                }
                result = dev->dma_queue_write(num_dw*2, num_dw*2, num_dw*2);
                if (result == 0) {
                    cout << "Could not queue DMA write! Aborting DMA.." << endl;
                    break;
                }
            }
            result = dev->dma_send_all();

            if (result != 0) {
                simul_period = dev->get_ktimer();
                cumul_simul_time += simul_period;
                simul_err_cnt = rp_ep_compare(dev, kdata, 0, num_dw);
                if (simul_err_cnt) {
                    cout << "Simul DMA encountered " << simul_err_cnt << " errors." << endl;
                    result = 0;
                }
            }
        }

        rd_perf[0]    = payload_bytes/(rd_period*1000.0);
        wr_perf[0]    = payload_bytes/(wr_period*1000.0);
        simul_perf[0] = payload_bytes/(simul_period*1000.0);
        rd_perf[1]    = payload_bytes*cur_run/(cumul_rd_time*1000.0);
        wr_perf[1]    = payload_bytes*cur_run/(cumul_wr_time*1000.0);
        simul_perf[1] = payload_bytes*cur_run/(cumul_simul_time*1000.0);

        system("clear");
        dma_show_config(run_rd, run_wr, run_simul, num_dw, num_desc);
        dma_show_perf(run_rd, run_wr, run_simul, num_dw, num_desc, cur_run,
                      rd_perf, wr_perf, simul_perf);

        if (result == 0) {
            cout << "Stopping DMA run due to error.." << endl;
            break;
        }
        try {
            if (kbhit()) {
                cin.ignore(1);
                break;
            }
        } catch (std::system_error& ex) {
            system("tset"); // Reset terminal window
            throw;
        }
    }

    result = dev->kmem_munmap(mmap_addr, num_dw*4);
    if (result != 1) {
        cout << "Could not unmap kernel memory!" << endl;
        return;
    }
}

static void dma_show_perf(bool run_rd, bool run_wr, bool run_simul,
                          unsigned int num_dw, unsigned int num_desc,
                          unsigned int cur_run, double (&rd_perf)[2],
                          double (&wr_perf)[2], double (&simul_perf)[2])
{
    time_t rawtime;
    std::ios_base::fmtflags f(cout.flags()); // Record initial flags
    time(&rawtime);
    cout << dec << noshowbase << setfill(' ') << internal;
    cout << fixed << setprecision(2);
    cout << "\n\n";
    cout << "Current run #: " << cur_run << endl;
    cout << "Current time : " << ctime(&rawtime) << endl;
    cout << "DMA throughputs, in GB/s (10^9B/s)" << endl;
    if (run_rd) {
        cout << "    Current Read Throughput   : " << setw(5) << rd_perf[0] << endl;
        cout << "    Average Read Throughput   : " << setw(5) << rd_perf[1] << endl;
    }
    if (run_wr) {
        cout << "    Current Write Throughput  : " << setw(5) << wr_perf[0] << endl;
        cout << "    Average Write Throughput  : " << setw(5) << wr_perf[1] << endl;
    }
    if (run_simul) {
        cout << "    Current Simul Throughput  : " << setw(5) << simul_perf[0] << endl;
        cout << "    Average Simul Throughput  : " << setw(5) << simul_perf[1] << endl;
    }
    cout << SEL_MENU_DELIMS << "\n";
    cout.flags(f); // Restore initial flags
}

static int kbhit(void)
{
    struct termios oldt, newt;
    int ch;
    int oldf;

    tcgetattr(STDIN_FILENO, &oldt);
    newt = oldt;
    newt.c_lflag &= ~(ICANON | ECHO);
    tcsetattr(STDIN_FILENO, TCSANOW, &newt);
    
    oldf = fcntl(STDIN_FILENO, F_GETFL, 0);
    if (oldf < 0) 
        throw std::system_error(errno, std::generic_category(), "Error reading stdin access mode");
    if (fcntl(STDIN_FILENO, F_SETFL, oldf | O_NONBLOCK) < 0)
        throw std::system_error(errno, std::generic_category(), "Error editing stdin access mode");

    ch = getchar();

    if (fcntl(STDIN_FILENO, F_SETFL, oldf) < 0)
        throw std::system_error(errno, std::generic_category(), "Error restoring stdin access mode");
    
    tcsetattr(STDIN_FILENO, TCSANOW, &oldt);
    
    if (ch == 27) {
        ungetc(ch, stdin);
        return 1;
    }

    return 0;
}

static void init_rp_mem(intel_fpga_pcie_dev *dev, uint32_t *addr,
                        unsigned int num_dw, unsigned int seed, int fill_mode)
{
    srand(seed);
    for (unsigned int i=0; i<num_dw; ++i) {
        if (fill_mode == FILL_RAND) {
            addr[i] = rand();
        } else if (fill_mode == FILL_INCR) {
            addr[i] = i;
        } else {
            addr[i] = 0;
        }
    }
}

static int init_ep_mem(intel_fpga_pcie_dev *dev, uint32_t *addr,
                       unsigned int num_dw, unsigned int seed, int fill_mode)
{
    int result;
    uint32_t temp_w;
    int wr_err_cnt = 0;

    srand(seed);
    for (unsigned int i=0; i<num_dw; ++i) {
        if (fill_mode == FILL_RAND) {
            temp_w = rand();
        } else if (fill_mode == FILL_INCR) {
            temp_w = i;
        } else {
            temp_w = 0;
        }

        result = dev->write32(2, reinterpret_cast<void *>(addr + i), temp_w);
        if (result == 0) {
            ++wr_err_cnt;
        }
    }
    return wr_err_cnt;
}

static int rp_ep_compare(intel_fpga_pcie_dev *dev, uint32_t *rp_addr,
                         uint32_t *ep_addr, unsigned int num_dw)
{
    int result;
    uint32_t temp_r;
    int data_mismatch_cnt = 0;

    for (unsigned int i=0; i<num_dw; ++i) {
        result = dev->read32(2, reinterpret_cast<void *>(ep_addr + i), &temp_r);
        if (result == 0 || (rp_addr[i] != temp_r)) {
            ++data_mismatch_cnt;
        }
    }

    return data_mismatch_cnt;
}

// Replicate pci_find_capability to reduce API clutter
// Returns 0 when no match
static uint32_t pci_find_capability(intel_fpga_pcie_dev *dev, int cap)
{
    int result;
    int num_caps_checked = 0;
    uint16_t read_data;
    uint8_t id;
    uint8_t pos = 0x34; // First next capability pointer.

    result = dev->cfg_read16(reinterpret_cast<void *>(0x6), &read_data);
    if (result != 1) {
        cout << "Could not read status register!" << endl;
        return 0;
    }
    if (!(read_data & 0x0010)) {
        cout << "Capability list not present!" << endl;
        return 0;
    }
    result = dev->cfg_read8(reinterpret_cast<void *>(0x34), &pos);
    if (result != 1) {
        cout << "Could not read capability list start pointer!" << endl;
        return 0;
    }

    // Check up to 24 capabilities ((256-64)/8)
    while (num_caps_checked < 24) {
        if (pos < 0x40) {
            cout << "Invalid capability location" << endl;
            return 0;
        }

        result = dev->cfg_read16(reinterpret_cast<void *>(pos), &read_data);
        if (result != 1) {
            cout << "Read failed!" << endl;
            return 0;
        }

        id = read_data & 0xFF;
        if (id == cap) {
            return pos;
        }
        pos = (read_data >> 8) & 0xFC; // Next pointer
        if (pos == 0) {
            // This was the last capability in the list.
            return 0;
        }
        ++num_caps_checked;
    }

    return 0;
}

// Replicate pci_find_ext_capability to reduce API clutter
// Returns 0 when no match.
static uint32_t pci_find_ext_capability(intel_fpga_pcie_dev *dev, int cap)
{
    int result;
    int num_caps_checked = 0;
    uint32_t read_data;
    uint16_t id;
    uint16_t pos;

    // Find PCI express capability.
    pos = pci_find_capability(dev, 0x10);
    if (pos == 0) {
        return 0;
    }
    pos = 0x100;    // Base of extended config space

    // Check up to 480 extended capabilities ((4096-256)/8)
    //  --> each cap is at least 8B, and extended config space is 4096 bytes.
    while (num_caps_checked < 480) {
        if (pos < 0x100) {
            cout << "Invalid extended capability location" << endl;
            return 0;
        }

        result = dev->cfg_read32(reinterpret_cast<void *>(pos), &read_data);
        if (result != 1) {
            cout << "Read failed!" << endl;
            return 0;
        }

        id = read_data & 0xFFFF;
        if (id == cap) {
            return pos;
        }
        pos = (read_data >> 20) & 0xFFC; // Next pointer
        if (pos == 0) {
            // This was the last capability in the list.
            return 0;
        }
        ++num_caps_checked;
    }

    return 0;
}
