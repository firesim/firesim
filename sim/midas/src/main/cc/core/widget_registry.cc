// See LICENSE for license details.

#include "widget_registry.h"
#include <cassert>

#include "bridges/fased_memory_timing_model.h"
#include "core/bridge_driver.h"
#include "core/stream_engine.h"

widget_registry_t::widget_registry_t() = default;

widget_registry_t::~widget_registry_t() = default;

void widget_registry_t::add_widget(widget_t *widget) {
  widgets[widget->kind].emplace_back(widget);
}

void widget_registry_t::add_widget(bridge_driver_t *widget) {
  widgets[widget->kind].emplace_back(widget);
  all_bridges.push_back(widget);
}

void widget_registry_t::add_widget(StreamEngine *widget) {
  fprintf(stdout, "widget_registry_t::add_widget(StreamEngine)\n");
  fprintf(stdout,
          "cpu2fpga: %d, fpga2cpu: %d\n",
          widget->cpu_to_fpga_cnt(),
          widget->fpga_to_cpu_cnt());

  if (get_stream_engine() == nullptr) {
    if (get_fpga_stream_engine() != nullptr) {
      fprintf(stdout, "PCIM stream engine driver registered before PCIS\n");
      assert(false);
    }
    stream_engine.reset(widget);
  } else if (get_fpga_stream_engine() == nullptr) {
    fpga_stream_engine.reset(widget);
  }
}
