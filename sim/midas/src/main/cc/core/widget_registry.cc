// See LICENSE for license details.

#include "widget_registry.h"

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

void widget_registry_t::add_widget(FASEDMemoryTimingModel *widget) {
  widgets[widget->kind].emplace_back(widget);
  all_models.push_back(widget);
}

void widget_registry_t::add_widget(StreamEngine *widget) {
  stream_engine.reset(widget);
}
