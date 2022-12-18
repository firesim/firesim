// See LICENSE for license details.

#include "widget_registry.h"

#include "core/bridge_driver.h"

#include "bridges/autocounter.h"
#include "bridges/clock.h"
#include "bridges/cpu_managed_stream.h"
#include "bridges/fased_memory_timing_model.h"
#include "bridges/fpga_managed_stream.h"
#include "bridges/fpga_model.h"
#include "bridges/loadmem.h"
#include "bridges/master.h"
#include "bridges/plusargs.h"
#include "bridges/reset_pulse.h"
#include "bridges/synthesized_assertions.h"
#include "bridges/synthesized_prints.h"
#include "bridges/termination.h"

#ifdef PEEKPOKEBRIDGEMODULE_0_PRESENT
#include "bridges/peek_poke.h"
#endif
#ifdef BLOCKDEVBRIDGEMODULE_0_PRESENT
#include "bridges/blockdev.h"
#endif
#ifdef DROMAJOBRIDGEMODULE_0_PRESENT
#include "bridges/dromajo.h"
#endif
#ifdef GROUNDTESTBRIDGEMODULE_0_PRESENT
#include "bridges/groundtest.h"
#endif
#ifdef SERIALBRIDGEMODULE_0_PRESENT
#include "bridges/serial.h"
#endif
#ifdef SIMPLENICBRIDGEMODULE_0_PRESENT
#include "bridges/simplenic.h"
#endif
#ifdef TRACERVBRIDGEMODULE_0_PRESENT
#include "bridges/tracerv.h"
#endif
#ifdef UARTBRIDGEMODULE_0_PRESENT
#include "bridges/uart.h"
#endif

widget_registry_t::widget_registry_t(const TargetConfig &config,
                                     simif_t &simif,
                                     const std::vector<std::string> &args)
    : config(config) {

  widget_registry_t &registry = *this;
#include "constructor.h"
}

widget_registry_t::~widget_registry_t() = default;

void widget_registry_t::add_widget(widget_t *widget) {
  widgets[widget->kind].emplace_back(widget);
}

void widget_registry_t::add_widget(bridge_driver_t *widget) {
  widgets[widget->kind].emplace_back(widget);
  all_bridges.push_back(widget);
}

void widget_registry_t::add_widget(FASEDMemoryTimingModel *widget) {
  widgets[widget->kind].emplace_back(widget);
  all_models.push_back(widget);
}

void widget_registry_t::add_widget(StreamEngine *widget) {
  stream_engine.reset(widget);
}
