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

#ifndef INTEL_FPGA_PCIE_LINK_TEST_HPP
#define INTEL_FPGA_PCIE_LINK_TEST_HPP

const int version_major = 2;
const int version_minor = 0;

#define WELCOME_OPT_AUTO        0
#define WELCOME_OPT_MANUAL      1
#define WELCOME_OPT_MAXNR       1

#define ACCESS_OPT_TEST         0
#define ACCESS_OPT_WR           1
#define ACCESS_OPT_RD           2
#define ACCESS_OPT_CFG_WR       3
#define ACCESS_OPT_CFG_RD       4
#define ACCESS_OPT_SEL_BAR      5
#define ACCESS_OPT_SEL_DEV      6
#define ACCESS_OPT_SRIOV_EN     7
#define ACCESS_OPT_SRIOV_TEST   8
#define ACCESS_OPT_DMA          9
#define ACCESS_OPT_QUIT         10
#define ACCESS_OPT_MAXNR        10

#define DMA_OPT_RUN             0
#define DMA_OPT_TOGGLE_RD       1
#define DMA_OPT_TOGGLE_WR       2
#define DMA_OPT_TOGGLE_SIMUL    3
#define DMA_OPT_MOD_NUM_DWORDS  4
#define DMA_OPT_MOD_NUM_DESCS   5
#define DMA_OPT_QUIT            6
#define DMA_OPT_MAXNR           6


#define SEL_MENU_DELIMS "*********************************************************"
#define FILL_ZERO 0
#define FILL_RAND 1
#define FILL_INCR 2

#endif /* INTEL_FPGA_PCIE_LINK_TEST_HPP */