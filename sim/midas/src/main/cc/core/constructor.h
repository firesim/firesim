// DOC include start: Bridge Driver Registration

// Helper file to be included in a method to instantiate all bridges available.
// Each bridge is constructed and passed to the `registry.add_widget` method.
// Top-levels can filter the bridges they want to add by creating overloads
// of the `registry.add_widget` method with appropriate types.

// Here we instantiate our driver once for each bridge in the target
// Golden Gate emits a <BridgeModuleClassName>_<id>_PRESENT macro for each
// instance which you may use to conditionally instantiate your driver.
// This file can be included in the setup method of any top-level to pass
// an instance of each driver to the `add_bridge_driver` method.

#ifdef CLOCKBRIDGEMODULE_checks
CLOCKBRIDGEMODULE_checks;
#endif // CLOCKBRIDGEMODULE_checks
#ifdef LOADMEMWIDGET_checks
LOADMEMWIDGET_checks;
#endif // LOADMEMWIDGET_checks
#ifdef SIMULATIONMASTER_checks
SIMULATIONMASTER_checks;
#endif // SIMULATIONMASTER_checks

#ifdef ASSERTBRIDGEMODULE_checks
ASSERTBRIDGEMODULE_checks;
#endif // ASSERTBRIDGEMODULE_checks

#ifdef TERMINATIONBRIDGEMODULE_checks
TERMINATIONBRIDGEMODULE_checks;
#endif // TERMINATIONBRIDGEMODULE_checks

#ifdef RESETPULSEBRIDGEMODULE_checks
RESETPULSEBRIDGEMODULE_checks;
#endif // RESETPULSEBRIDGEMODULE_checks

#ifdef FASEDMEMORYTIMINGMODEL_checks
FASEDMEMORYTIMINGMODEL_checks;
#endif

#ifdef AUTOCOUNTERBRIDGEMODULE_checks
AUTOCOUNTERBRIDGEMODULE_checks;
#endif // AUTOCOUNTERBRIDGEMODULE_checks

#ifdef PRINTBRIDGEMODULE_checks
PRINTBRIDGEMODULE_checks;
#endif // PRINTBRIDGEMODULE_checks

#ifdef PLUSARGSBRIDGEMODULE_checks
PLUSARGSBRIDGEMODULE_checks;
#endif // PLUSARGSBRIDGEMODULE_checks

#ifdef BLOCKDEVBRIDGEMODULE_checks
BLOCKDEVBRIDGEMODULE_checks;
#endif // BLOCKDEVBRIDGEMODULE_checks

#ifdef SERIALBRIDGEMODULE_checks
SERIALBRIDGEMODULE_checks;
#endif // SERIALBRIDGEMODULE_checks

#ifdef TRACERVBRIDGEMODULE_checks
TRACERVBRIDGEMODULE_checks;
#endif // TRACERVBRIDGEMODULE_checks

#ifdef SIMPLENICBRIDGEMODULE_checks
SIMPLENICBRIDGEMODULE_checks;
#endif // SIMPLENICBRIDGEMODULE_checks

#ifdef PEEKPOKEBRIDGEMODULE_checks
PEEKPOKEBRIDGEMODULE_checks;
#endif // PEEKPOKEBRIDGEMODULE_checks

#ifdef UARTBRIDGEMODULE_checks
UARTBRIDGEMODULE_checks;
#endif // UARTBRIDGEMODULE_checks

#ifdef LOADMEMWIDGET_0_PRESENT
registry.add_widget(new loadmem_t(simif,
                                  LOADMEMWIDGET_0_substruct_create,
                                  conf_target.mem,
                                  LOADMEMWIDGET_0_mem_data_chunk));
#endif // LOADMEMWIDGET_0_PRESENT

#ifdef CLOCKBRIDGEMODULE_0_PRESENT
registry.add_widget(new clockmodule_t(simif,
                                      CLOCKBRIDGEMODULE_0_substruct_create));
#endif // CLOCKBRIDGEMODULE_0_PRESENT

#ifdef SIMULATIONMASTER_0_PRESENT
registry.add_widget(new master_t(simif, SIMULATIONMASTER_0_substruct_create));
#endif // SIMULATIONMASTER_0_PRESENT

#ifdef CPUMANAGEDSTREAMENGINE_0_PRESENT
{
  std::vector<CPUManagedStreams::StreamParameters> from_cpu;
  for (size_t i = 0; i < CPUMANAGEDSTREAMENGINE_0_from_cpu_stream_count; i++) {
    from_cpu.emplace_back(
        std::string(CPUMANAGEDSTREAMENGINE_0_from_cpu_names[i]),
        CPUMANAGEDSTREAMENGINE_0_from_cpu_dma_addrs[i],
        CPUMANAGEDSTREAMENGINE_0_from_cpu_count_addrs[i],
        CPUMANAGEDSTREAMENGINE_0_from_cpu_buffer_sizes[i]);
  }

  std::vector<CPUManagedStreams::StreamParameters> to_cpu;
  for (size_t i = 0; i < CPUMANAGEDSTREAMENGINE_0_to_cpu_stream_count; i++) {
    to_cpu.emplace_back(std::string(CPUMANAGEDSTREAMENGINE_0_to_cpu_names[i]),
                        CPUMANAGEDSTREAMENGINE_0_to_cpu_dma_addrs[i],
                        CPUMANAGEDSTREAMENGINE_0_to_cpu_count_addrs[i],
                        CPUMANAGEDSTREAMENGINE_0_to_cpu_buffer_sizes[i]);
  }
  registry.add_widget(
      new CPUManagedStreamWidget(simif.get_cpu_managed_stream_io(),
                                 std::move(from_cpu),
                                 std::move(to_cpu)));
}
#endif // CPUMANAGEDSTREAMENGINE_0_PRESENT

#ifdef FPGAMANAGEDSTREAMENGINE_0_PRESENT
{
  std::vector<FPGAManagedStreams::StreamParameters> to_cpu;
  for (size_t i = 0; i < FPGAMANAGEDSTREAMENGINE_0_to_cpu_stream_count; i++) {
    to_cpu.emplace_back(FPGAManagedStreams::StreamParameters(
        std::string(FPGAMANAGEDSTREAMENGINE_0_to_cpu_names[i]),
        FPGAMANAGEDSTREAMENGINE_0_to_cpu_fpgaBufferDepth[i],
        FPGAMANAGEDSTREAMENGINE_0_to_cpu_toHostPhysAddrHighAddrs[i],
        FPGAMANAGEDSTREAMENGINE_0_to_cpu_toHostPhysAddrLowAddrs[i],
        FPGAMANAGEDSTREAMENGINE_0_to_cpu_bytesAvailableAddrs[i],
        FPGAMANAGEDSTREAMENGINE_0_to_cpu_bytesConsumedAddrs[i],
        FPGAMANAGEDSTREAMENGINE_0_to_cpu_toHostStreamDoneInitAddrs[i],
        FPGAMANAGEDSTREAMENGINE_0_to_cpu_toHostStreamFlushAddrs[i],
        FPGAMANAGEDSTREAMENGINE_0_to_cpu_toHostStreamFlushDoneAddrs[i]));
  }
  registry.add_widget(new FPGAManagedStreamWidget(
      simif.get_fpga_managed_stream_io(), std::move(to_cpu)));
}
#endif // FPGAMANAGEDSTREAMENGINE_0_PRESENT

#define INSTANTIATE_RESET_PULSE(FUNC, IDX)                                     \
  registry.add_widget(                                                         \
      new reset_pulse_t(simif,                                                 \
                        args,                                                  \
                        RESETPULSEBRIDGEMODULE_##IDX##_substruct_create,       \
                        RESETPULSEBRIDGEMODULE_##IDX##_max_pulse_length,       \
                        RESETPULSEBRIDGEMODULE_##IDX##_default_pulse_length,   \
                        IDX));

#ifdef RESETPULSEBRIDGEMODULE_0_PRESENT
INSTANTIATE_RESET_PULSE(registry.add_widget, 0)
#endif

#ifdef PEEKPOKEBRIDGEMODULE_0_PRESENT
registry.add_widget(new peek_poke_t(simif,
                                    PEEKPOKEBRIDGEMODULE_0_substruct_create,
                                    POKE_SIZE,
                                    (const uint32_t *)INPUT_ADDRS,
                                    (const char *const *)INPUT_NAMES,
                                    (const uint32_t *)INPUT_CHUNKS,
                                    PEEK_SIZE,
                                    (const uint32_t *)OUTPUT_ADDRS,
                                    (const char *const *)OUTPUT_NAMES,
                                    (const uint32_t *)OUTPUT_CHUNKS));
#endif

#ifdef UARTBRIDGEMODULE_0_PRESENT
registry.add_widget(
    new uart_t(simif, args, UARTBRIDGEMODULE_0_substruct_create, 0));
#endif
#ifdef UARTBRIDGEMODULE_1_PRESENT
registry.add_widget(
    new uart_t(simif, args, UARTBRIDGEMODULE_1_substruct_create, 1));
#endif
#ifdef UARTBRIDGEMODULE_2_PRESENT
registry.add_widget(
    new uart_t(simif, args, UARTBRIDGEMODULE_2_substruct_create, 2));
#endif
#ifdef UARTBRIDGEMODULE_3_PRESENT
registry.add_widget(
    new uart_t(simif, args, UARTBRIDGEMODULE_3_substruct_create, 3));
#endif
#ifdef UARTBRIDGEMODULE_4_PRESENT
registry.add_widget(
    new uart_t(simif, args, UARTBRIDGEMODULE_4_substruct_create, 4));
#endif
#ifdef UARTBRIDGEMODULE_5_PRESENT
registry.add_widget(
    new uart_t(simif, args, UARTBRIDGEMODULE_5_substruct_create, 5));
#endif
#ifdef UARTBRIDGEMODULE_6_PRESENT
registry.add_widget(
    new uart_t(simif, args, UARTBRIDGEMODULE_6_substruct_create, 6));
#endif
#ifdef UARTBRIDGEMODULE_7_PRESENT
registry.add_widget(
    new uart_t(simif, args, UARTBRIDGEMODULE_7_substruct_create, 7));
#endif

#define INSTANTIATE_FASED(IDX)                                                 \
  registry.add_widget(new FASEDMemoryTimingModel(                              \
      simif,                                                                   \
      AddressMap(FASEDMEMORYTIMINGMODEL_##IDX##_R_num_registers,               \
                 (const unsigned int *)FASEDMEMORYTIMINGMODEL_##IDX##_R_addrs, \
                 (const char *const *)FASEDMEMORYTIMINGMODEL_##IDX##_R_names,  \
                 FASEDMEMORYTIMINGMODEL_##IDX##_W_num_registers,               \
                 (const unsigned int *)FASEDMEMORYTIMINGMODEL_##IDX##_W_addrs, \
                 (const char *const *)FASEDMEMORYTIMINGMODEL_##IDX##_W_names), \
      args,                                                                    \
      "memory_stats" #IDX ".csv",                                              \
      1L << FASEDMEMORYTIMINGMODEL_##IDX##_target_addr_bits,                   \
      "_" #IDX));

#ifdef FASEDMEMORYTIMINGMODEL_0
INSTANTIATE_FASED(0)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_1
INSTANTIATE_FASED(1)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_2
INSTANTIATE_FASED(2)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_3
INSTANTIATE_FASED(3)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_4
INSTANTIATE_FASED(4)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_5
INSTANTIATE_FASED(5)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_6
INSTANTIATE_FASED(6)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_7
INSTANTIATE_FASED(7)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_8
INSTANTIATE_FASED(8)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_9
INSTANTIATE_FASED(9)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_10
INSTANTIATE_FASED(registry.add_widget, 10)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_11
INSTANTIATE_FASED(registry.add_widget, 11)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_12
INSTANTIATE_FASED(registry.add_widget, 12)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_13
INSTANTIATE_FASED(registry.add_widget, 13)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_14
INSTANTIATE_FASED(registry.add_widget, 14)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_15
INSTANTIATE_FASED(registry.add_widget, 15)
#endif

#define INSTANTIATE_SERIAL(FUNC, IDX)                                          \
  FUNC(new serial_t(simif,                                                     \
                    args,                                                      \
                    SERIALBRIDGEMODULE_##IDX##_substruct_create,               \
                    registry.get_widget<loadmem_t>(),                          \
                    SERIALBRIDGEMODULE_##IDX##_has_memory,                     \
                    SERIALBRIDGEMODULE_##IDX##_memory_offset,                  \
                    IDX));

#ifdef SERIALBRIDGEMODULE_0_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 0)
#endif
#ifdef SERIALBRIDGEMODULE_1_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 1)
#endif
#ifdef SERIALBRIDGEMODULE_2_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 2)
#endif
#ifdef SERIALBRIDGEMODULE_3_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 3)
#endif
#ifdef SERIALBRIDGEMODULE_4_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 4)
#endif
#ifdef SERIALBRIDGEMODULE_5_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 5)
#endif
#ifdef SERIALBRIDGEMODULE_6_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 6)
#endif
#ifdef SERIALBRIDGEMODULE_7_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 7)
#endif
#ifdef SERIALBRIDGEMODULE_8_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 8)
#endif
#ifdef SERIALBRIDGEMODULE_9_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 9)
#endif
#ifdef SERIALBRIDGEMODULE_10_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 10)
#endif
#ifdef SERIALBRIDGEMODULE_11_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 11)
#endif
#ifdef SERIALBRIDGEMODULE_12_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 12)
#endif
#ifdef SERIALBRIDGEMODULE_13_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 13)
#endif
#ifdef SERIALBRIDGEMODULE_14_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 14)
#endif
#ifdef SERIALBRIDGEMODULE_15_PRESENT
INSTANTIATE_SERIAL(registry.add_widget, 15)
#endif

#ifdef BLOCKDEVBRIDGEMODULE_0_PRESENT
registry.add_widget(new blockdev_t(simif,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_0_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_0_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_0_substruct_create,
                                   0));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_1_PRESENT
registry.add_widget(new blockdev_t(simif,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_1_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_1_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_1_substruct_create,
                                   1));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_2_PRESENT
registry.add_widget(new blockdev_t(simif,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_2_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_2_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_2_substruct_create,
                                   2));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_3_PRESENT
registry.add_widget(new blockdev_t(simif,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_3_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_3_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_3_substruct_create,
                                   3));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_4_PRESENT
registry.add_widget(new blockdev_t(simif,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_4_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_4_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_4_substruct_create,
                                   4));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_5_PRESENT
registry.add_widget(new blockdev_t(simif,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_5_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_5_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_5_substruct_create,
                                   5));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_6_PRESENT
registry.add_widget(new blockdev_t(simif,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_6_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_6_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_6_substruct_create,
                                   6));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_7_PRESENT
registry.add_widget(new blockdev_t(simif,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_7_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_7_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_7_substruct_create,
                                   7));
#endif

#define INSTANTIATE_SIMPLENIC(FUNC, IDX)                                       \
  FUNC(new simplenic_t(simif,                                                  \
                       *registry.get_stream_engine(),                          \
                       args,                                                   \
                       SIMPLENICBRIDGEMODULE_##IDX##_substruct_create,         \
                       IDX,                                                    \
                       SIMPLENICBRIDGEMODULE_##IDX##_to_cpu_stream_idx,        \
                       SIMPLENICBRIDGEMODULE_##IDX##_to_cpu_stream_depth,      \
                       SIMPLENICBRIDGEMODULE_##IDX##_from_cpu_stream_idx,      \
                       SIMPLENICBRIDGEMODULE_##IDX##_from_cpu_stream_depth));

#ifdef SIMPLENICBRIDGEMODULE_0_PRESENT
INSTANTIATE_SIMPLENIC(registry.add_widget, 0)
#endif
#ifdef SIMPLENICBRIDGEMODULE_1_PRESENT
INSTANTIATE_SIMPLENIC(registry.add_widget, 1)
#endif
#ifdef SIMPLENICBRIDGEMODULE_2_PRESENT
INSTANTIATE_SIMPLENIC(registry.add_widget, 2)
#endif
#ifdef SIMPLENICBRIDGEMODULE_3_PRESENT
INSTANTIATE_SIMPLENIC(registry.add_widget, 3)
#endif
#ifdef SIMPLENICBRIDGEMODULE_4_PRESENT
INSTANTIATE_SIMPLENIC(registry.add_widget, 4)
#endif
#ifdef SIMPLENICBRIDGEMODULE_5_PRESENT
INSTANTIATE_SIMPLENIC(registry.add_widget, 5)
#endif
#ifdef SIMPLENICBRIDGEMODULE_6_PRESENT
INSTANTIATE_SIMPLENIC(registry.add_widget, 6)
#endif
#ifdef SIMPLENICBRIDGEMODULE_7_PRESENT
INSTANTIATE_SIMPLENIC(registry.add_widget, 7)
#endif

#define INSTANTIATE_TRACERV(FUNC, IDX)                                         \
  FUNC(new tracerv_t(simif,                                                    \
                     *registry.get_stream_engine(),                            \
                     args,                                                     \
                     TRACERVBRIDGEMODULE_##IDX##_substruct_create,             \
                     TRACERVBRIDGEMODULE_##IDX##_to_cpu_stream_idx,            \
                     TRACERVBRIDGEMODULE_##IDX##_to_cpu_stream_depth,          \
                     TRACERVBRIDGEMODULE_##IDX##_max_core_ipc,                 \
                     TRACERVBRIDGEMODULE_##IDX##_clock_domain_name,            \
                     TRACERVBRIDGEMODULE_##IDX##_clock_multiplier,             \
                     TRACERVBRIDGEMODULE_##IDX##_clock_divisor,                \
                     IDX));

#ifdef TRACERVBRIDGEMODULE_0_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 0)
#endif
#ifdef TRACERVBRIDGEMODULE_1_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 1)
#endif
#ifdef TRACERVBRIDGEMODULE_2_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 2)
#endif
#ifdef TRACERVBRIDGEMODULE_3_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 3)
#endif
#ifdef TRACERVBRIDGEMODULE_4_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 4)
#endif
#ifdef TRACERVBRIDGEMODULE_5_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 5)
#endif
#ifdef TRACERVBRIDGEMODULE_6_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 6)
#endif
#ifdef TRACERVBRIDGEMODULE_7_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 7)
#endif
#ifdef TRACERVBRIDGEMODULE_8_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 8)
#endif
#ifdef TRACERVBRIDGEMODULE_9_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 9)
#endif
#ifdef TRACERVBRIDGEMODULE_10_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 10)
#endif
#ifdef TRACERVBRIDGEMODULE_11_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 11)
#endif
#ifdef TRACERVBRIDGEMODULE_12_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 12)
#endif
#ifdef TRACERVBRIDGEMODULE_13_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 13)
#endif
#ifdef TRACERVBRIDGEMODULE_14_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 14)
#endif
#ifdef TRACERVBRIDGEMODULE_15_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 15)
#endif
#ifdef TRACERVBRIDGEMODULE_16_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 16)
#endif
#ifdef TRACERVBRIDGEMODULE_17_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 17)
#endif
#ifdef TRACERVBRIDGEMODULE_18_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 18)
#endif
#ifdef TRACERVBRIDGEMODULE_19_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 19)
#endif
#ifdef TRACERVBRIDGEMODULE_20_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 20)
#endif
#ifdef TRACERVBRIDGEMODULE_21_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 21)
#endif
#ifdef TRACERVBRIDGEMODULE_22_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 22)
#endif
#ifdef TRACERVBRIDGEMODULE_23_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 23)
#endif
#ifdef TRACERVBRIDGEMODULE_24_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 24)
#endif
#ifdef TRACERVBRIDGEMODULE_25_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 25)
#endif
#ifdef TRACERVBRIDGEMODULE_26_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 26)
#endif
#ifdef TRACERVBRIDGEMODULE_27_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 27)
#endif
#ifdef TRACERVBRIDGEMODULE_28_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 28)
#endif
#ifdef TRACERVBRIDGEMODULE_29_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 29)
#endif
#ifdef TRACERVBRIDGEMODULE_30_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 30)
#endif
#ifdef TRACERVBRIDGEMODULE_31_PRESENT
INSTANTIATE_TRACERV(registry.add_widget, 31)
#endif

#ifdef DROMAJOBRIDGEMODULE_0_PRESENT
    add_bridge_driver(new dromajo_t(
            *simif, args,
            DROMAJOBRIDGEMODULE_0_substruct_create,
            dromajo_config_t{
                .resetVector = DROMAJO_RESET_VECTOR,
                .mmioStart = DROMAJO_MMIO_START,
                .mmioEnd = DROMAJO_MMIO_END,
                .memSize = DROMAJO_MEM_SIZE,
                .plicBase = DROMAJO_PLIC_BASE,
                .plicSize = DROMAJO_PLIC_SIZE,
                .clintBase = DROMAJO_CLINT_BASE,
                .clintSize = DROMAJO_CLINT_SIZE,
            },
            DROMAJOBRIDGEMODULE_0_iaddr_width,
            DROMAJOBRIDGEMODULE_0_insn_width,
            DROMAJOBRIDGEMODULE_0_wdata_width,
            DROMAJOBRIDGEMODULE_0_cause_width,
            DROMAJOBRIDGEMODULE_0_tval_width,
            DROMAJOBRIDGEMODULE_0_num_traces,
            DROMAJOBRIDGEMODULE_0_to_cpu_stream_dma_address,
            DROMAJOBRIDGEMODULE_0_to_cpu_stream_count_address,
            DROMAJOBRIDGEMODULE_0_to_cpu_stream_full_address)
#endif

#ifdef GROUNDTESTBRIDGEMODULE_0_PRESENT
    registry.add_widget(new groundtest_t(
            simif, args, GROUNDTESTBRIDGEMODULE_0_substruct_create));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_1_PRESENT
    registry.add_widget(new groundtest_t(
            simif, args, GROUNDTESTBRIDGEMODULE_1_substruct_create));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_2_PRESENT
    registry.add_widget(new groundtest_t(
            simif, args, GROUNDTESTBRIDGEMODULE_2_substruct_create));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_3_PRESENT
    registry.add_widget(new groundtest_t(
            simif, args, GROUNDTESTBRIDGEMODULE_3_substruct_create));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_4_PRESENT
    registry.add_widget(new groundtest_t(
            simif, args, GROUNDTESTBRIDGEMODULE_4_substruct_create));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_5_PRESENT
    registry.add_widget(new groundtest_t(
            simif, args, GROUNDTESTBRIDGEMODULE_5_substruct_create));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_6_PRESENT
    registry.add_widget(new groundtest_t(
            simif, args, GROUNDTESTBRIDGEMODULE_6_substruct_create));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_7_PRESENT
    registry.add_widget(new groundtest_t(
            simif, args, GROUNDTESTBRIDGEMODULE_7_substruct_create));
#endif

#define INSTANTIATE_AUTOCOUNTER(FUNC, IDX)                                     \
  FUNC(new autocounter_t(                                                      \
      simif,                                                                   \
      args,                                                                    \
      AUTOCOUNTERBRIDGEMODULE_##IDX##_substruct_create,                        \
      AddressMap(                                                              \
          AUTOCOUNTERBRIDGEMODULE_##IDX##_R_num_registers,                     \
          (const unsigned int *)AUTOCOUNTERBRIDGEMODULE_##IDX##_R_addrs,       \
          (const char *const *)AUTOCOUNTERBRIDGEMODULE_##IDX##_R_names,        \
          AUTOCOUNTERBRIDGEMODULE_##IDX##_W_num_registers,                     \
          (const unsigned int *)AUTOCOUNTERBRIDGEMODULE_##IDX##_W_addrs,       \
          (const char *const *)AUTOCOUNTERBRIDGEMODULE_##IDX##_W_names),       \
      AUTOCOUNTERBRIDGEMODULE_##IDX##_event_count,                             \
      AUTOCOUNTERBRIDGEMODULE_##IDX##_event_types,                             \
      AUTOCOUNTERBRIDGEMODULE_##IDX##_event_widths,                            \
      AUTOCOUNTERBRIDGEMODULE_##IDX##_accumulator_widths,                      \
      AUTOCOUNTERBRIDGEMODULE_##IDX##_event_addr_hi,                           \
      AUTOCOUNTERBRIDGEMODULE_##IDX##_event_addr_lo,                           \
      AUTOCOUNTERBRIDGEMODULE_##IDX##_event_descriptions,                      \
      AUTOCOUNTERBRIDGEMODULE_##IDX##_event_labels,                            \
      AUTOCOUNTERBRIDGEMODULE_##IDX##_clock_domain_name,                       \
      AUTOCOUNTERBRIDGEMODULE_##IDX##_clock_multiplier,                        \
      AUTOCOUNTERBRIDGEMODULE_##IDX##_clock_divisor,                           \
      IDX));

#ifdef AUTOCOUNTERBRIDGEMODULE_0_PRESENT
    INSTANTIATE_AUTOCOUNTER(registry.add_widget, 0)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_1_PRESENT
    INSTANTIATE_AUTOCOUNTER(registry.add_widget, 1)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_2_PRESENT
    INSTANTIATE_AUTOCOUNTER(registry.add_widget, 2)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_3_PRESENT
    INSTANTIATE_AUTOCOUNTER(registry.add_widget, 3)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_4_PRESENT
    INSTANTIATE_AUTOCOUNTER(registry.add_widget, 4)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_5_PRESENT
    INSTANTIATE_AUTOCOUNTER(registry.add_widget, 5)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_6_PRESENT
    INSTANTIATE_AUTOCOUNTER(registry.add_widget, 6)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_7_PRESENT
    INSTANTIATE_AUTOCOUNTER(registry.add_widget, 7)
#endif

#ifdef ASSERTBRIDGEMODULE_0_PRESENT
    registry.add_widget(new synthesized_assertions_t(simif, args,
                                                   ASSERTBRIDGEMODULE_0_substruct_create,
                                                   ASSERTBRIDGEMODULE_0_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_1_PRESENT
    registry.add_widget(new synthesized_assertions_t(simif, args,
                                                   ASSERTBRIDGEMODULE_1_substruct_create,
                                                   ASSERTBRIDGEMODULE_1_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_2_PRESENT
    registry.add_widget(new synthesized_assertions_t(simif, args,
                                                   ASSERTBRIDGEMODULE_2_substruct_create,
                                                   ASSERTBRIDGEMODULE_2_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_3_PRESENT
    registry.add_widget(new synthesized_assertions_t(simif, args,
                                                   ASSERTBRIDGEMODULE_3_substruct_create,
                                                   ASSERTBRIDGEMODULE_3_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_4_PRESENT
    registry.add_widget(new synthesized_assertions_t(simif, args,
                                                   ASSERTBRIDGEMODULE_4_substruct_create,
                                                   ASSERTBRIDGEMODULE_4_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_5_PRESENT
    registry.add_widget(new synthesized_assertions_t(simif, args,
                                                   ASSERTBRIDGEMODULE_5_substruct_create,
                                                   ASSERTBRIDGEMODULE_5_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_6_PRESENT
    registry.add_widget(new synthesized_assertions_t(simif, args,
                                                   ASSERTBRIDGEMODULE_6_substruct_create,
                                                   ASSERTBRIDGEMODULE_6_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_7_PRESENT
    registry.add_widget(new synthesized_assertions_t(simif, args,
                                                   ASSERTBRIDGEMODULE_7_substruct_create,
                                                   ASSERTBRIDGEMODULE_7_assert_messages));
#endif

#define INSTANTIATE_PRINTF(FUNC, IDX)                                          \
  FUNC(new synthesized_prints_t(simif,                                         \
                                *registry.get_stream_engine(),                 \
                                args,                                          \
                                PRINTBRIDGEMODULE_##IDX##_substruct_create,    \
                                PRINTBRIDGEMODULE_##IDX##_print_count,         \
                                PRINTBRIDGEMODULE_##IDX##_token_bytes,         \
                                PRINTBRIDGEMODULE_##IDX##_idle_cycles_mask,    \
                                PRINTBRIDGEMODULE_##IDX##_print_offsets,       \
                                PRINTBRIDGEMODULE_##IDX##_format_strings,      \
                                PRINTBRIDGEMODULE_##IDX##_argument_counts,     \
                                PRINTBRIDGEMODULE_##IDX##_argument_widths,     \
                                PRINTBRIDGEMODULE_##IDX##_to_cpu_stream_idx,   \
                                PRINTBRIDGEMODULE_##IDX##_to_cpu_stream_depth, \
                                PRINTBRIDGEMODULE_##IDX##_clock_domain_name,   \
                                PRINTBRIDGEMODULE_##IDX##_clock_multiplier,    \
                                PRINTBRIDGEMODULE_##IDX##_clock_divisor,       \
                                IDX));

#ifdef PRINTBRIDGEMODULE_0_PRESENT
    INSTANTIATE_PRINTF(registry.add_widget,0)
#endif
#ifdef PRINTBRIDGEMODULE_1_PRESENT
    INSTANTIATE_PRINTF(registry.add_widget,1)
#endif
#ifdef PRINTBRIDGEMODULE_2_PRESENT
    INSTANTIATE_PRINTF(registry.add_widget,2)
#endif
#ifdef PRINTBRIDGEMODULE_3_PRESENT
    INSTANTIATE_PRINTF(registry.add_widget,3)
#endif
#ifdef PRINTBRIDGEMODULE_4_PRESENT
    INSTANTIATE_PRINTF(registry.add_widget,4)
#endif
#ifdef PRINTBRIDGEMODULE_5_PRESENT
    INSTANTIATE_PRINTF(registry.add_widget,5)
#endif
#ifdef PRINTBRIDGEMODULE_6_PRESENT
    INSTANTIATE_PRINTF(registry.add_widget,6)
#endif
#ifdef PRINTBRIDGEMODULE_7_PRESENT
    INSTANTIATE_PRINTF(registry.add_widget,7)
#endif

#define INSTANTIATE_PLUSARGS(FUNC, IDX)                                        \
  FUNC(new plusargs_t(simif,                                                   \
                      args,                                                    \
                      PLUSARGSBRIDGEMODULE_##IDX##_substruct_create,           \
                      PLUSARGSBRIDGEMODULE_##IDX##_name,                       \
                      PLUSARGSBRIDGEMODULE_##IDX##_default,                    \
                      PLUSARGSBRIDGEMODULE_##IDX##_width,                      \
                      PLUSARGSBRIDGEMODULE_##IDX##_slice_count,                \
                      PLUSARGSBRIDGEMODULE_##IDX##_slice_addrs));

#ifdef PLUSARGSBRIDGEMODULE_0_PRESENT
    INSTANTIATE_PLUSARGS(registry.add_widget, 0)
#endif
#ifdef PLUSARGSBRIDGEMODULE_1_PRESENT
    INSTANTIATE_PLUSARGS(registry.add_widget, 1)
#endif
#ifdef PLUSARGSBRIDGEMODULE_2_PRESENT
    INSTANTIATE_PLUSARGS(registry.add_widget, 2)
#endif
#ifdef PLUSARGSBRIDGEMODULE_3_PRESENT
    INSTANTIATE_PLUSARGS(registry.add_widget, 3)
#endif
#ifdef PLUSARGSBRIDGEMODULE_4_PRESENT
    INSTANTIATE_PLUSARGS(registry.add_widget, 4)
#endif
#ifdef PLUSARGSBRIDGEMODULE_5_PRESENT
    INSTANTIATE_PLUSARGS(registry.add_widget, 5)
#endif
#ifdef PLUSARGSBRIDGEMODULE_6_PRESENT
    INSTANTIATE_PLUSARGS(registry.add_widget, 6)
#endif
#ifdef PLUSARGSBRIDGEMODULE_7_PRESENT
    INSTANTIATE_PLUSARGS(registry.add_widget, 7)
#endif

#define INSTANTIATE_TERMINATION(FUNC, IDX)                                     \
  FUNC(new termination_t(simif,                                                \
                         args,                                                 \
                         TERMINATIONBRIDGEMODULE_##IDX##_substruct_create,     \
                         TERMINATIONBRIDGEMODULE_##IDX##_message_count,        \
                         TERMINATIONBRIDGEMODULE_##IDX##_message_type,         \
                         TERMINATIONBRIDGEMODULE_##IDX##_message));

#ifdef TERMINATIONBRIDGEMODULE_0_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 0)
#endif
#ifdef TERMINATIONBRIDGEMODULE_1_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 1)
#endif
#ifdef TERMINATIONBRIDGEMODULE_2_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 2)
#endif
#ifdef TERMINATIONBRIDGEMODULE_3_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 3)
#endif
#ifdef TERMINATIONBRIDGEMODULE_4_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 4)
#endif
#ifdef TERMINATIONBRIDGEMODULE_5_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 5)
#endif
#ifdef TERMINATIONBRIDGEMODULE_6_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 6)
#endif
#ifdef TERMINATIONBRIDGEMODULE_7_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 7)
#endif
#ifdef TERMINATIONBRIDGEMODULE_8_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 8)
#endif
#ifdef TERMINATIONBRIDGEMODULE_9_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 9)
#endif
#ifdef TERMINATIONBRIDGEMODULE_10_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 10)
#endif
#ifdef TERMINATIONBRIDGEMODULE_11_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 11)
#endif
#ifdef TERMINATIONBRIDGEMODULE_12_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 12)
#endif
#ifdef TERMINATIONBRIDGEMODULE_13_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 13)
#endif
#ifdef TERMINATIONBRIDGEMODULE_14_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 14)
#endif
#ifdef TERMINATIONBRIDGEMODULE_15_PRESENT
  INSTANTIATE_TERMINATION(registry.add_widget, 15)
#endif
// DOC include end: Bridge Driver Registration
