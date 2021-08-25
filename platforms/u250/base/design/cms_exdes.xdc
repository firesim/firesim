

# (c) Copyright 2020 Xilinx, Inc. All rights reserved.
#
# This file contains confidential and proprietary information
# of Xilinx, Inc. and is protected under U.S. and
# international copyright and other intellectual property
# laws.
#
# DISCLAIMER
# This disclaimer is not a license and does not grant any
# rights to the materials distributed herewith. Except as
# otherwise provided in a valid license issued to you by
# Xilinx, and to the maximum extent permitted by applicable
# law: (1) THESE MATERIALS ARE MADE AVAILABLE "AS IS" AND
# WITH ALL FAULTS, AND XILINX HEREBY DISCLAIMS ALL WARRANTIES
# AND CONDITIONS, EXPRESS, IMPLIED, OR STATUTORY, INCLUDING
# BUT NOT LIMITED TO WARRANTIES OF MERCHANTABILITY, NON-
# INFRINGEMENT, OR FITNESS FOR ANY PARTICULAR PURPOSE; and
# (2) Xilinx shall not be liable (whether in contract or tort,
# including negligence, or under any other theory of
# liability) for any loss or damage of any kind or nature
# related to, arising under or in connection with these
# materials, including for any direct, or any indirect,
# special, incidental, or consequential loss or damage
# (including loss of data, profits, goodwill, or any type of
# loss or damage suffered as a result of any action brought
# by a third party) even if such damage or loss was
# reasonably foreseeable or Xilinx had been advised of the
# possibility of the same.
#
# CRITICAL APPLICATIONS
# Xilinx products are not designed or intended to be fail-
# safe, or for use in any application requiring fail-safe
# performance, such as life-support or safety devices or
# systems, Class III medical devices, nuclear facilities,
# applications related to the deployment of airbags, or any
# other applications that could lead to death, personal
# injury, or severe property or environmental damage
# (individually and collectively, "Critical
# Applications"). Customer assumes the sole risk and
# liability of any use of Xilinx products in Critical
# Applications, subject only to applicable laws and
# regulations governing limitations on product liability.
#
# THIS COPYRIGHT NOTICE AND DISCLAIMER MUST BE RETAINED AS
# PART OF THIS FILE AT ALL TIMES.
############################################################

# Clock constraints
# ------------------------------------------------------------------------------
create_clock -period 10.0 -name ref_clk [get_ports pcie_mgt_clk_p[0]]
create_clock -period 3.333 -name ddr0_ref_clk [get_ports SYSCLK0_300_clk_p]

# Configuration
# ------------------------------------------------------------------------------
set_property CONFIG_VOLTAGE 1.8                        [current_design]
set_property BITSTREAM.CONFIG.CONFIGFALLBACK Enable    [current_design]
set_property BITSTREAM.GENERAL.COMPRESS TRUE           [current_design]
set_property CONFIG_MODE SPIx4                         [current_design]
set_property BITSTREAM.CONFIG.SPI_BUSWIDTH 4           [current_design]
set_property BITSTREAM.CONFIG.EXTMASTERCCLK_EN disable [current_design]
set_property BITSTREAM.CONFIG.CONFIGRATE 85.0          [current_design]
set_property BITSTREAM.CONFIG.SPI_FALL_EDGE YES        [current_design]
set_property BITSTREAM.CONFIG.UNUSEDPIN Pullup         [current_design]
set_property BITSTREAM.CONFIG.SPI_32BIT_ADDR Yes       [current_design]

# IO constraints


    # ------------------------------------------------------------------------------
    # U200 & U250
    # ------------------------------------------------------------------------------
    # ref_clk
    set_property PACKAGE_PIN AM11                     [get_ports {pcie_mgt_clk_p[0]}]

    # pcie_perstn_rst
    set_property PACKAGE_PIN BD21                     [get_ports pcie_perstn_rst]
    set_property -dict {IOSTANDARD LVCMOS12}          [get_ports pcie_perstn_rst]

    # Satellite Controller UART
    set_property PACKAGE_PIN BA19                     [get_ports satellite_uart_rxd]
    set_property -dict {IOSTANDARD LVCMOS12}          [get_ports satellite_uart_rxd]
    set_property PACKAGE_PIN BB19                     [get_ports satellite_uart_txd]
    set_property -dict {IOSTANDARD LVCMOS12 DRIVE 4}  [get_ports satellite_uart_txd]

    # Satellite Controller GPIO
    set_property PACKAGE_PIN AR20                     [get_ports satellite_gpio[0]]
    set_property -dict {IOSTANDARD LVCMOS12}          [get_ports satellite_gpio[0]]
    set_property PACKAGE_PIN AM20                     [get_ports satellite_gpio[1]]
    set_property -dict {IOSTANDARD LVCMOS12}          [get_ports satellite_gpio[1]]
    set_property PACKAGE_PIN AM21                     [get_ports satellite_gpio[2]]
    set_property -dict {IOSTANDARD LVCMOS12}          [get_ports satellite_gpio[2]]
    set_property PACKAGE_PIN AN21                     [get_ports satellite_gpio[3]]
    set_property -dict {IOSTANDARD LVCMOS12}          [get_ports satellite_gpio[3]]

    #__old__|# QSFP / I2C Control
    #__old__|set_property PACKAGE_PIN BE16                   [get_ports qsfp0_modsel_l   ]
    #__old__|set_property IOSTANDARD  LVCMOS12               [get_ports qsfp0_modsel_l   ]
    #__old__|set_property PACKAGE_PIN BE17                   [get_ports qsfp0_reset_l    ]
    #__old__|set_property IOSTANDARD  LVCMOS12               [get_ports qsfp0_reset_l    ]
    #__old__|set_property PACKAGE_PIN BD18                   [get_ports qsfp0_lpmode     ]
    #__old__|set_property IOSTANDARD  LVCMOS12               [get_ports qsfp0_lpmode     ]
    #__old__|set_property PACKAGE_PIN BE20                   [get_ports qsfp0_modprs_l   ]
    #__old__|set_property IOSTANDARD  LVCMOS12               [get_ports qsfp0_modprs_l   ]
    #__old__|set_property PACKAGE_PIN BE21                   [get_ports qsfp0_int_l      ]
    #__old__|set_property IOSTANDARD  LVCMOS12               [get_ports qsfp0_int_l      ]
    #__old__|
    #__old__|set_property PACKAGE_PIN AY20                   [get_ports qsfp1_modsel_l   ]
    #__old__|set_property IOSTANDARD  LVCMOS12               [get_ports qsfp1_modsel_l   ]
    #__old__|set_property PACKAGE_PIN BC18                   [get_ports qsfp1_reset_l    ]
    #__old__|set_property IOSTANDARD  LVCMOS12               [get_ports qsfp1_reset_l    ]
    #__old__|set_property PACKAGE_PIN AV22                   [get_ports qsfp1_lpmode     ]
    #__old__|set_property IOSTANDARD  LVCMOS12               [get_ports qsfp1_lpmode     ]
    #__old__|set_property PACKAGE_PIN BC19                   [get_ports qsfp1_modprs_l   ]
    #__old__|set_property IOSTANDARD  LVCMOS12               [get_ports qsfp1_modprs_l   ]
    #__old__|set_property PACKAGE_PIN AV21                   [get_ports qsfp1_int_l      ]
    #__old__|set_property IOSTANDARD  LVCMOS12               [get_ports qsfp1_int_l      ]

    # QSFP / I2C Control
    set_property PACKAGE_PIN BE16                   [get_ports qsfp0_modsel_l_0[0]  ]
    set_property IOSTANDARD  LVCMOS12               [get_ports qsfp0_modsel_l_0[0]  ]
    set_property PACKAGE_PIN BE17                   [get_ports qsfp0_reset_l_0[0]   ]
    set_property IOSTANDARD  LVCMOS12               [get_ports qsfp0_reset_l_0[0]   ]
    set_property PACKAGE_PIN BD18                   [get_ports qsfp0_lpmode_0[0]    ]
    set_property IOSTANDARD  LVCMOS12               [get_ports qsfp0_lpmode_0[0]    ]
    set_property PACKAGE_PIN BE20                   [get_ports qsfp0_modprs_l_0[0]  ]
    set_property IOSTANDARD  LVCMOS12               [get_ports qsfp0_modprs_l_0[0]  ]
    set_property PACKAGE_PIN BE21                   [get_ports qsfp0_int_l_0[0]     ]
    set_property IOSTANDARD  LVCMOS12               [get_ports qsfp0_int_l_0[0]     ]

    set_property PACKAGE_PIN AY20                   [get_ports qsfp1_modsel_l_0[0]  ]
    set_property IOSTANDARD  LVCMOS12               [get_ports qsfp1_modsel_l_0[0]  ]
    set_property PACKAGE_PIN BC18                   [get_ports qsfp1_reset_l_0[0]   ]
    set_property IOSTANDARD  LVCMOS12               [get_ports qsfp1_reset_l_0[0]   ]
    set_property PACKAGE_PIN AV22                   [get_ports qsfp1_lpmode_0[0]    ]
    set_property IOSTANDARD  LVCMOS12               [get_ports qsfp1_lpmode_0[0]    ]
    set_property PACKAGE_PIN BC19                   [get_ports qsfp1_modprs_l_0[0]  ]
    set_property IOSTANDARD  LVCMOS12               [get_ports qsfp1_modprs_l_0[0]  ]
    set_property PACKAGE_PIN AV21                   [get_ports qsfp1_int_l_0[0]     ]
    set_property IOSTANDARD  LVCMOS12               [get_ports qsfp1_int_l_0[0]     ]

    ## ------------------------------------------------------------------------------
    ## U250
    ## ------------------------------------------------------------------------------
    set_property LOC PCIE40E4_X0Y1 [get_cells ex_synth_i/xdma_0/inst/pcie4_ip_i/inst/pcie_4_0_pipe_inst/pcie_4_0_e4_inst]

# Biancolin: Everything below is copied from the U250 reference XDC from the Alveo Lounge
#

#
#   AU200/250 - Master XDC
#
#       FPGA PIN reference are with respect to the U200 FPGA Bank naming
#            The FPGA A200 and A250 are pin for pin compatible devices.
#               +----------------+---------------+---------------+---------------+
#               | A250 Bank      | A200 Bank     | Usage         | Voltage       |
#               +----------------+---------------+---------------+---------------+ 
#               | Bank 61,62,63  | Bank 40,41,42 | DDR4 C0 Int.  | 1.2V          |
#               +----------------+---------------+---------------+---------------+ 
#               | Bank 65,66,67  | Bank 65,66,67 | DDR4 C1 Int.  | 1.2V          |
#               +----------------+---------------+---------------+---------------+ 
#               | Bank 69,70,71  | Bank 46,47,48 | DDR4 C2 Int.  | 1.2V          |
#               +----------------+---------------+---------------+---------------+ 
#               | Bank 72,73,74  | Bank 70,71,72 | DDR4 C3 Int.  | 1.2V          |
#               +----------------+---------------+---------------+---------------+ 
#               | Bank 64        | Bank 64       | Misc. IO      | 1.2V          |
#               +----------------+---------------+---------------+---------------+ 
#               | Bank 231       | Bank 231      | QSFP0         | NA            |
#               +----------------+---------------+---------------+---------------+ 
#               | Bank 230       | Bank 230      | QSFP1         | NA            |  
#               +----------------+---------------+---------------+---------------+ 
#               | Bank 224-227   | Bank 224-227  | PCIE          | NA            |  
#               +----------------+---------------+---------------+---------------+ 
#
#
#   Key Notes:
#       1) Power warning constraint set to warn user if design exceeds 160 Watts
#       3) Refer to XAPP1321 for DDR4 Self refresh and fast calibration.
#
#   Clock Trees
#
#    1) SI570 - SiLabs 570BAB000544DG @ 156.250Mhz Programmable Oscillator (Re-programming I2C access via Bank 64 I2C )
#
#      - OUT---> SI570_OUTPUT_P/SI570_OUTPUT_N @ 156.250Mhz LVDS
#           |
#           |--> SI53340-B-GM --> OUT0  USER_SI570_CLOCK_P/USER_SI570_CLOCK_N 156.250Mhz - General Perpose System Clock.
#                             |   PINS: IO_L12P_T1U_N10_GC_64_AU19/IO_L12N_T1U_N11_GC_64_AV19
#                             |
#                             |-> OUT1  Not Connected
#                             |   PINS: NA
#                             |
#                             |-> OUT2  MGT_SI570_CLOCK0_C_P/MGT_SI570_CLOCK0_C_N 156.250Mhz - QSFP0 REFCLK0
#                             |   PINS: MGTREFCLK0P_231_M11/MGTREFCLK0N_231_M10
#                             |
#                             |-> OUT3  MGT_SI570_CLOCK1_C_P/MGT_SI570_CLOCK1_C_N 156.250Mhz - QSFP0 REFCLK1
#                                 PINS: MGTREFCLK0P_230_T11/MGTREFCLK0N_230_T10
#
#    2) SI335A - SiLabs SI5335A-B06201-GM Selectable output Oscillator 156.2500Mhz/161.1328125Mhz For QSFP0 REFCLK1
#
#      - FS[1:0] <-- Clock Select Pin FS[1:0] = 1X -> 161.132812 MHz 1.8V LVDS (default when FPGA pin Hi-Z due to 10K pullups)
#                                     FS[1:0] = 01 -> 156.250000 MHz 1.8V LVDS
#                PINS: "QSFP0_FS[0]"         - IO_L10P_T1U_N6_QBC_AD4P_64_AT20
#                PINS: "QSFP0_FS[1]"         - IO_L9N_T1L_N5_AD12N_64_AU22
#
#      - RESET <-- Device Reset - Asserting this pin (driving high) is required to change FS1,FS0 pin setting. 
#                PINS: "QSFP0_RECLK_RESET"   - IO_L9P_T1L_N4_AD12P_64_AT22
#
#      - OUT0--> SYSCLK_300_P/SYSCLK_300_N @ 300.0000Mhz to 1-to-4 Clock buffer (Fixed and Unchanged by FS[1:0])
#           |
#           |--> SI53340-B-GM --> OUT0  SYSCLK0_300_P/SYSCLK0_300_N 300.000Mhz - System Clock for first DDR4 MIG interface
#                             |   PINS: IO_L13P_T2L_N0_GC_QBC_63_AY37/IO_L13N_T2L_N1_GC_QBC_63_AY38
#                             |
#                             |-> OUT1  SYSCLK1_300_P/SYSCLK1_300_N 300.000Mhz - System Clock for second DDR4 MIG interface.
#                             |   PINS: IO_L11P_T1U_N8_GC_64_AW20/IO_L11N_T1U_N9_GC_64_AW19
#                             |
#                             |-> OUT2  SYSCLK2_300_P/SYSCLK2_300_N 300.000Mhz - System Clock for third DDR4 MIG interface.
#                             |   PINS: IO_L13P_T2L_N0_GC_QBC_70_F32/IO_L13N_T2L_N1_GC_QBC_70_E32
#                             |
#                             |-> OUT3  SYSCLK3_300_P/SYSCLK3_300_N 300.000Mhz - System Clock for fourth DDR4 MIG interface.
#                                 PINS: IO_L13P_T2L_N0_GC_QBC_72_J16/IO_L13N_T2L_N1_GC_QBC_72_H16
#
#
#      - OUT1--> QSFP0_CLOCK_P/QSFP0_CLOCK_N @ 161.1328125Mhz (Selectable based on state of FS[1:0])
#                PINS: MGTREFCLK1P_231_K11/MGTREFCLK1N_231_K10
#
#      - OUT2--> QSFP0_CLOCK_P/QSFP0_CLOCK_N @ 90.0000Mhz (Fixed and Unchanged by FS[1:0])
#                PINS: Not Connected
#
#      - OUT3--> QSFP0_CLOCK_P/QSFP0_CLOCK_N @ 33.0000Mhz (Fixed and Unchanged by FS[1:0])
#                PINS: Not Connected
#
#    3) SI335A - SiLabs SI5335A-B06201-GM Selectable output Oscillator 156.2500Mhz/161.1328125Mhz For QSFP1 REFCLK1
#
#      - FS[1:0] <-- Clock Select Pin FS[1:0] = 1X -> 161.132812 MHz 1.8V LVDS (default when FPGA pin Hi-Z due to 10K pullups)
#                                     FS[1:0] = 01 -> 156.250000 MHz 1.8V LVDS
#                PINS: "QSFP1_FS[0]"         - IO_L8P_T1L_N2_AD5P_64_AR22
#                PINS: "QSFP1_FS[1]"         - IO_L7N_T1L_N1_QBC_AD13N_64_AU20
#
#      - RESET <-- Device Reset - Asserting this pin (driving high) is required to change FS1,FS0 pin setting. 
#                PINS: "QSFP1_RECLK_RESET"   - IO_L8N_T1L_N3_AD5N_64_AR21
#
#      - OUT0--> 300.0000Mhz (Fixed and Unchanged by FS[1:0])
#                PINS: Not Connected
#
#      - OUT1--> QSFP1_CLOCK_P/QSFP1_CLOCK_N @ 161.1328125Mhz (Selectable based on state of FS[1:0])
#                PINS: MGTREFCLK1P_230_P11/MGTREFCLK1N_230_P10
#
#      - OUT2--> 90.0000Mhz (Fixed and Unchanged by FS[1:0])
#                PINS: Not Connected
#
#      - OUT3--> 33.0000Mhz (Fixed and Unchanged by FS[1:0])
#                PINS: Not Connected
#
#   4) PCIE Fingers PEX_REFCLK_P/PEX_REFCLK_P 100.000Mhz
#           PINS: MGTREFCLK0P_226_AM11/MGTREFCLK0N_226_AM10
#
#  Revision 1.00 - Intial Release for AU200/250
#  Revision 2.00 - Updated XDC with card details.
#  Revision 2.01 - Fixed modified QSFP1 IO Standards from POD12_DCI to LVCMOS18
#                  Added Configuration Constraints
#  Revision 2.02 - Fixed Bank 64 IOstandards from LVCMOS18 to LVCMOS12.
#  Revision 2.03 - Fixed pins E38 and F38 IOstandards from DIFF_SSTL12_DCI to POD12_DC.
#
#################################################################################

#
# LVDS Input SYSTEM CLOCKS for Memory Interfaces
#
# Biancolin: Pin names changed here to match bd
set_property -dict {PACKAGE_PIN AY38 IOSTANDARD DIFF_POD12_DCI } [get_ports SYSCLK0_300_clk_n    ]; # Bank 42 VCCO - VCC1V2 Net "SYSCLK0_300_N" - IO_L13N_T2L_N1_GC_QBC_42
set_property -dict {PACKAGE_PIN AY37 IOSTANDARD DIFF_POD12_DCI } [get_ports SYSCLK0_300_clk_p    ]; # Bank 42 VCCO - VCC1V2 Net "SYSCLK0_300_P" - IO_L13P_T2L_N0_GC_QBC_42

#
# DDR4 RDIMM Controller 0, 72-bit Data Interface, x4 Componets, Single Rank
#     <<<NOTE>>> DQS Clock strobes have been swapped from JEDEC standard to match Xilinx MIG Clock order:
#                JEDEC Order   DQS ->  0  9  1 10  2 11  3 12  4 13  5 14  6 15  7 16  8 17
#                Xil MIG Order DQS ->  0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16 17
#
set_property -dict {PACKAGE_PIN AR36 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[16]  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR16"   - IO_L23N_T3U_N9_42
set_property -dict {PACKAGE_PIN AP36 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[15]  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR15"   - IO_L23P_T3U_N8_42
#set_property -dict {PACKAGE_PIN AN34 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_odt[1]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ODT1"    - IO_L22N_T3U_N7_DBC_AD0N_42
#set_property -dict {PACKAGE_PIN AM34 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_cs_n[3]  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_CS_B3"   - IO_L22P_T3U_N6_DBC_AD0P_42
set_property -dict {PACKAGE_PIN AR33 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_cs_n  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_CS_B0"   - IO_T3U_N12_42
set_property -dict {PACKAGE_PIN AN36 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[13]  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR13"   - IO_L24N_T3U_N11_42
#set_property -dict {PACKAGE_PIN AN35 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[17]  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR17"   - IO_L24P_T3U_N10_42
set_property -dict {PACKAGE_PIN AP35 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[14]  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR14"   - IO_L21N_T3L_N5_AD8N_42
set_property -dict {PACKAGE_PIN AP34 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_odt   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ODT0"    - IO_L21P_T3L_N4_AD8P_42
#set_property -dict {PACKAGE_PIN AP33 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_cs_n[1]  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_CS_B1"   - IO_L20N_T3L_N3_AD1N_42
#set_property -dict {PACKAGE_PIN AN33 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_cs_n[2]  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_CS_B2"   - IO_L20P_T3L_N2_AD1P_42
set_property -dict {PACKAGE_PIN AT35 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_ba[0]    ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_BA0"     - IO_L19N_T3L_N1_DBC_AD9N_42
set_property -dict {PACKAGE_PIN AR35 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[10]  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR10"   - IO_L19P_T3L_N0_DBC_AD9P_42
set_property -dict {PACKAGE_PIN AW38 IOSTANDARD DIFF_SSTL12_DCI} [get_ports c0_ddr4_ck_c  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_CK_C0"   - IO_L17N_T2U_N9_AD10N_42
set_property -dict {PACKAGE_PIN AV38 IOSTANDARD DIFF_SSTL12_DCI} [get_ports c0_ddr4_ck_t  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_CK_T0"   - IO_L17P_T2U_N8_AD10P_42
#set_property -dict {PACKAGE_PIN AU35 IOSTANDARD DIFF_SSTL12_DCI} [get_ports c0_ddr4_ck_c[1]  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_CK_C1"   - IO_L16N_T2U_N7_QBC_AD3N_42
#set_property -dict {PACKAGE_PIN AU34 IOSTANDARD DIFF_SSTL12_DCI} [get_ports c0_ddr4_ck_t[1]  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_CK_T1"   - IO_L16P_T2U_N6_QBC_AD3P_42
set_property -dict {PACKAGE_PIN AT34 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_ba[1]    ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_BA1"     - IO_T2U_N12_42
set_property -dict {PACKAGE_PIN AU36 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_par   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_PAR"     - IO_L18N_T2U_N11_AD2N_42
set_property -dict {PACKAGE_PIN AT36 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[0]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR0"    - IO_L18P_T2U_N10_AD2P_42
set_property -dict {PACKAGE_PIN AV37 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[2]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR2"    - IO_L15N_T2L_N5_AD11N_42
set_property -dict {PACKAGE_PIN AV36 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[1]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR1"    - IO_L15P_T2L_N4_AD11P_42
set_property -dict {PACKAGE_PIN AW36 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[4]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR4"    - IO_L14N_T2L_N3_GC_42
set_property -dict {PACKAGE_PIN AW35 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[3]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR3"    - IO_L14P_T2L_N2_GC_42
# Biancolin: set_property -dict {PACKAGE_PIN BA38 IOSTANDARD LVCMOS12       } [get_ports c0_ddr4_alert_n  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ALERT_B" - IO_L11N_T1U_N9_GC_42
set_property -dict {PACKAGE_PIN BA37 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[8]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR8"    - IO_L11P_T1U_N8_GC_42
set_property -dict {PACKAGE_PIN BA40 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[7]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR7"    - IO_L10N_T1U_N7_QBC_AD4N_42
set_property -dict {PACKAGE_PIN BA39 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[11]  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR11"   - IO_L10P_T1U_N6_QBC_AD4P_42
set_property -dict {PACKAGE_PIN BB37 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[9]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR9"    - IO_T1U_N12_42
set_property -dict {PACKAGE_PIN AY36 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[5]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR5"    - IO_L12N_T1U_N11_GC_42
set_property -dict {PACKAGE_PIN AY35 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[6]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR6"    - IO_L12P_T1U_N10_GC_42
#set_property -dict {PACKAGE_PIN BC40 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_cke[1]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_CKE1"    - IO_L9N_T1L_N5_AD12N_42
set_property -dict {PACKAGE_PIN BC39 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_bg[1]    ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_BG1"     - IO_L9P_T1L_N4_AD12P_42
set_property -dict {PACKAGE_PIN BB40 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_adr[12]  ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ADR12"   - IO_L8N_T1L_N3_AD5N_42
set_property -dict {PACKAGE_PIN BB39 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_act_n    ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_ACT_B"   - IO_L8P_T1L_N2_AD5P_42
set_property -dict {PACKAGE_PIN BC38 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_cke   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_CKE0"    - IO_L7N_T1L_N1_QBC_AD13N_42
set_property -dict {PACKAGE_PIN BC37 IOSTANDARD SSTL12_DCI     } [get_ports c0_ddr4_bg[0]    ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_BG0"     - IO_L7P_T1L_N0_QBC_AD13P_42
set_property -dict {PACKAGE_PIN BF43 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[66]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_DQ66"    - IO_L5N_T0U_N9_AD14N_42
set_property -dict {PACKAGE_PIN BF42 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[67]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_DQ67"    - IO_L5P_T0U_N8_AD14P_42
set_property -dict {PACKAGE_PIN BF38 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[16]]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_DQS_C8"  - IO_L4N_T0U_N7_DBC_AD7N_42
set_property -dict {PACKAGE_PIN BE38 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[16]]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_DQS_T8"  - IO_L4P_T0U_N6_DBC_AD7P_42
set_property -dict {PACKAGE_PIN BD40 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[64]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_DQ64"    - IO_L6N_T0U_N11_AD6N_42
set_property -dict {PACKAGE_PIN BD39 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[65]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_DQ65"    - IO_L6P_T0U_N10_AD6P_42
set_property -dict {PACKAGE_PIN BF41 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[71]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_DQ71"    - IO_L3N_T0L_N5_AD15N_42
set_property -dict {PACKAGE_PIN BE40 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[70]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_DQ70"    - IO_L3P_T0L_N4_AD15P_42
set_property -dict {PACKAGE_PIN BF37 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[68]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_DQ68"    - IO_L2N_T0L_N3_42
set_property -dict {PACKAGE_PIN BE37 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[69]   ]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_DQ69"    - IO_L2P_T0L_N2_42
set_property -dict {PACKAGE_PIN BF40 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[17]]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_DQS_C17" - IO_L1N_T0L_N1_DBC_42
set_property -dict {PACKAGE_PIN BF39 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[17]]; # Bank 42 VCCO - VCC1V2 Net "DDR4_C0_DQS_T17" - IO_L1P_T0L_N0_DBC_42
set_property -dict {PACKAGE_PIN AU32 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[34]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ34"    - IO_L23N_T3U_N9_41
set_property -dict {PACKAGE_PIN AT32 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[35]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ35"    - IO_L23P_T3U_N8_41
set_property -dict {PACKAGE_PIN AM32 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[8] ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_C4"  - IO_L22N_T3U_N7_DBC_AD0N_41
set_property -dict {PACKAGE_PIN AM31 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[8] ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_T4"  - IO_L22P_T3U_N6_DBC_AD0P_41
#set_property -dict {PACKAGE_PIN AT33 IOSTANDARD LVCMOS12       } [get_ports c0_ddr4_event_n  ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_EVENT_B" - IO_T3U_N12_41
set_property -dict {PACKAGE_PIN AM30 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[33]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ33"    - IO_L24N_T3U_N11_41
set_property -dict {PACKAGE_PIN AL30 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[32]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ32"    - IO_L24P_T3U_N10_41
set_property -dict {PACKAGE_PIN AR32 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[38]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ38"    - IO_L21N_T3L_N5_AD8N_41
set_property -dict {PACKAGE_PIN AR31 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[39]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ39"    - IO_L21P_T3L_N4_AD8P_41
set_property -dict {PACKAGE_PIN AN32 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[37]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ37"    - IO_L20N_T3L_N3_AD1N_41
set_property -dict {PACKAGE_PIN AN31 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[36]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ36"    - IO_L20P_T3L_N2_AD1P_41
set_property -dict {PACKAGE_PIN AP31 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[9] ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_C13" - IO_L19N_T3L_N1_DBC_AD9N_41
set_property -dict {PACKAGE_PIN AP30 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[9] ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_T13" - IO_L19P_T3L_N0_DBC_AD9P_41
set_property -dict {PACKAGE_PIN AV32 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[25]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ25"    - IO_L17N_T2U_N9_AD10N_41
set_property -dict {PACKAGE_PIN AV31 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[24]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ24"    - IO_L17P_T2U_N8_AD10P_41
set_property -dict {PACKAGE_PIN AW33 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[6] ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_C3"  - IO_L16N_T2U_N7_QBC_AD3N_41
set_property -dict {PACKAGE_PIN AV33 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[6] ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_T3"  - IO_L16P_T2U_N6_QBC_AD3P_41
set_property -dict {PACKAGE_PIN AU31 IOSTANDARD LVCMOS12       } [get_ports c0_ddr4_reset_n  ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_RESET_N" - IO_T2U_N12_41
set_property -dict {PACKAGE_PIN AW34 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[27]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ27"    - IO_L18N_T2U_N11_AD2N_41
set_property -dict {PACKAGE_PIN AV34 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[26]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ26"    - IO_L18P_T2U_N10_AD2P_41
set_property -dict {PACKAGE_PIN AY31 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[29]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ29"    - IO_L15N_T2L_N5_AD11N_41
set_property -dict {PACKAGE_PIN AW31 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[28]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ28"    - IO_L15P_T2L_N4_AD11P_41
set_property -dict {PACKAGE_PIN BA35 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[30]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ30"    - IO_L14N_T2L_N3_GC_41
set_property -dict {PACKAGE_PIN BA34 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[31]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ31"    - IO_L14P_T2L_N2_GC_41
set_property -dict {PACKAGE_PIN BA33 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[7] ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_C12" - IO_L13N_T2L_N1_GC_QBC_41
set_property -dict {PACKAGE_PIN BA32 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[7] ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_T12" - IO_L13P_T2L_N0_GC_QBC_41
set_property -dict {PACKAGE_PIN BB32 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[17]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ17"    - IO_L11N_T1U_N9_GC_41
set_property -dict {PACKAGE_PIN BB31 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[16]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ16"    - IO_L11P_T1U_N8_GC_41
set_property -dict {PACKAGE_PIN BB36 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[4] ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_C2"  - IO_L10N_T1U_N7_QBC_AD4N_41
set_property -dict {PACKAGE_PIN BB35 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[4] ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_T2"  - IO_L10P_T1U_N6_QBC_AD4P_41
set_property -dict {PACKAGE_PIN AY33 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[19]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ19"    - IO_L12N_T1U_N11_GC_41
set_property -dict {PACKAGE_PIN AY32 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[18]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ18"    - IO_L12P_T1U_N10_GC_41
set_property -dict {PACKAGE_PIN BC33 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[21]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ21"    - IO_L9N_T1L_N5_AD12N_41
set_property -dict {PACKAGE_PIN BC32 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[20]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ20"    - IO_L9P_T1L_N4_AD12P_41
set_property -dict {PACKAGE_PIN BC34 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[23]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ23"    - IO_L8N_T1L_N3_AD5N_41
set_property -dict {PACKAGE_PIN BB34 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[22]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ22"    - IO_L8P_T1L_N2_AD5P_41
set_property -dict {PACKAGE_PIN BD31 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[5] ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_C11" - IO_L7N_T1L_N1_QBC_AD13N_41
set_property -dict {PACKAGE_PIN BC31 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[5] ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_T11" - IO_L7P_T1L_N0_QBC_AD13P_41
set_property -dict {PACKAGE_PIN BE33 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[58]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ58"    - IO_L5N_T0U_N9_AD14N_41
set_property -dict {PACKAGE_PIN BD33 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[57]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ57"    - IO_L5P_T0U_N8_AD14P_41
set_property -dict {PACKAGE_PIN BE36 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[14]]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_C7"  - IO_L4N_T0U_N7_DBC_AD7N_41
set_property -dict {PACKAGE_PIN BE35 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[14]]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_T7"  - IO_L4P_T0U_N6_DBC_AD7P_41
set_property -dict {PACKAGE_PIN BD35 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[59]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ59"    - IO_L6N_T0U_N11_AD6N_41
set_property -dict {PACKAGE_PIN BD34 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[56]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ56"    - IO_L6P_T0U_N10_AD6P_41
set_property -dict {PACKAGE_PIN BF33 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[61]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ61"    - IO_L3N_T0L_N5_AD15N_41
set_property -dict {PACKAGE_PIN BF32 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[60]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ60"    - IO_L3P_T0L_N4_AD15P_41
set_property -dict {PACKAGE_PIN BF35 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[63]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ63"    - IO_L2N_T0L_N3_41
set_property -dict {PACKAGE_PIN BF34 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[62]   ]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQ62"    - IO_L2P_T0L_N2_41
set_property -dict {PACKAGE_PIN BE32 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[15]]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_C16" - IO_L1N_T0L_N1_DBC_41
set_property -dict {PACKAGE_PIN BE31 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[15]]; # Bank 41 VCCO - VCC1V2 Net "DDR4_C0_DQS_T16" - IO_L1P_T0L_N0_DBC_41
set_property -dict {PACKAGE_PIN AP29 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[40]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ40"    - IO_L23N_T3U_N9_40
set_property -dict {PACKAGE_PIN AP28 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[41]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ41"    - IO_L23P_T3U_N8_40
set_property -dict {PACKAGE_PIN AL29 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[10]]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_C5"  - IO_L22N_T3U_N7_DBC_AD0N_40
set_property -dict {PACKAGE_PIN AL28 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[10]]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_T5"  - IO_L22P_T3U_N6_DBC_AD0P_40
set_property -dict {PACKAGE_PIN AN27 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[42]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ42"    - IO_L24N_T3U_N11_40
set_property -dict {PACKAGE_PIN AM27 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[43]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ43"    - IO_L24P_T3U_N10_40
set_property -dict {PACKAGE_PIN AR28 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[47]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ47"    - IO_L21N_T3L_N5_AD8N_40
set_property -dict {PACKAGE_PIN AR27 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[46]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ46"    - IO_L21P_T3L_N4_AD8P_40
set_property -dict {PACKAGE_PIN AN29 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[44]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ44"    - IO_L20N_T3L_N3_AD1N_40
set_property -dict {PACKAGE_PIN AM29 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[45]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ45"    - IO_L20P_T3L_N2_AD1P_40
set_property -dict {PACKAGE_PIN AT30 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[11]]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_C14" - IO_L19N_T3L_N1_DBC_AD9N_40
set_property -dict {PACKAGE_PIN AR30 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[11]]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_T14" - IO_L19P_T3L_N0_DBC_AD9P_40
set_property -dict {PACKAGE_PIN AV27 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[49]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ49"    - IO_L17N_T2U_N9_AD10N_40
set_property -dict {PACKAGE_PIN AU27 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[50]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ50"    - IO_L17P_T2U_N8_AD10P_40
set_property -dict {PACKAGE_PIN AU30 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[12]]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_C6"  - IO_L16N_T2U_N7_QBC_AD3N_40
set_property -dict {PACKAGE_PIN AU29 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[12]]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_T6"  - IO_L16P_T2U_N6_QBC_AD3P_40
set_property -dict {PACKAGE_PIN AT28 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[48]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ48"    - IO_L18N_T2U_N11_AD2N_40
set_property -dict {PACKAGE_PIN AT27 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[51]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ51"    - IO_L18P_T2U_N10_AD2P_40
set_property -dict {PACKAGE_PIN AV29 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[52]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ52"    - IO_L15N_T2L_N5_AD11N_40
set_property -dict {PACKAGE_PIN AV28 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[55]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ55"    - IO_L15P_T2L_N4_AD11P_40
set_property -dict {PACKAGE_PIN AY30 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[53]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ53"    - IO_L14N_T2L_N3_GC_40
set_property -dict {PACKAGE_PIN AW30 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[54]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ54"    - IO_L14P_T2L_N2_GC_40
set_property -dict {PACKAGE_PIN AY28 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[13]]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_C15" - IO_L13N_T2L_N1_GC_QBC_40
set_property -dict {PACKAGE_PIN AY27 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[13]]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_T15" - IO_L13P_T2L_N0_GC_QBC_40
set_property -dict {PACKAGE_PIN BA28 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[2]    ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ2"     - IO_L11N_T1U_N9_GC_40
set_property -dict {PACKAGE_PIN BA27 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[3]    ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ3"     - IO_L11P_T1U_N8_GC_40
set_property -dict {PACKAGE_PIN BB30 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[0] ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_C0"  - IO_L10N_T1U_N7_QBC_AD4N_40
set_property -dict {PACKAGE_PIN BA30 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[0] ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_T0"  - IO_L10P_T1U_N6_QBC_AD4P_40
set_property -dict {PACKAGE_PIN AW29 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[1]    ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ1"     - IO_L12N_T1U_N11_GC_40
set_property -dict {PACKAGE_PIN AW28 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[0]    ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ0"     - IO_L12P_T1U_N10_GC_40
set_property -dict {PACKAGE_PIN BC27 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[6]    ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ6"     - IO_L9N_T1L_N5_AD12N_40
set_property -dict {PACKAGE_PIN BB27 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[7]    ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ7"     - IO_L9P_T1L_N4_AD12P_40
set_property -dict {PACKAGE_PIN BB29 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[4]    ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ4"     - IO_L8N_T1L_N3_AD5N_40
set_property -dict {PACKAGE_PIN BA29 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[5]    ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ5"     - IO_L8P_T1L_N2_AD5P_40
set_property -dict {PACKAGE_PIN BC26 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[1] ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_C9"  - IO_L7N_T1L_N1_QBC_AD13N_40
set_property -dict {PACKAGE_PIN BB26 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[1] ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_T9"  - IO_L7P_T1L_N0_QBC_AD13P_40
set_property -dict {PACKAGE_PIN BF28 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[9]    ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ9"     - IO_L5N_T0U_N9_AD14N_40
set_property -dict {PACKAGE_PIN BE28 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[8]    ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ8"     - IO_L5P_T0U_N8_AD14P_40
set_property -dict {PACKAGE_PIN BD29 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[2] ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_C1"  - IO_L4N_T0U_N7_DBC_AD7N_40
set_property -dict {PACKAGE_PIN BD28 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[2] ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_T1"  - IO_L4P_T0U_N6_DBC_AD7P_40
set_property -dict {PACKAGE_PIN BE30 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[10]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ10"    - IO_L6N_T0U_N11_AD6N_40
set_property -dict {PACKAGE_PIN BD30 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[11]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ11"    - IO_L6P_T0U_N10_AD6P_40
set_property -dict {PACKAGE_PIN BF27 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[12]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ12"    - IO_L3N_T0L_N5_AD15N_40
set_property -dict {PACKAGE_PIN BE27 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[13]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ13"    - IO_L3P_T0L_N4_AD15P_40
set_property -dict {PACKAGE_PIN BF30 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[14]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ14"    - IO_L2N_T0L_N3_40
set_property -dict {PACKAGE_PIN BF29 IOSTANDARD POD12_DCI      } [get_ports c0_ddr4_dq[15]   ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQ15"    - IO_L2P_T0L_N2_40
set_property -dict {PACKAGE_PIN BE26 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_c[3] ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_C10" - IO_L1N_T0L_N1_DBC_40
set_property -dict {PACKAGE_PIN BD26 IOSTANDARD DIFF_POD12_DCI } [get_ports c0_ddr4_dqs_t[3] ]; # Bank 40 VCCO - VCC1V2 Net "DDR4_C0_DQS_T10" - IO_L1P_T0L_N0_DBC_40









