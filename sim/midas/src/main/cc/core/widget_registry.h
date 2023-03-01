// See LICENSE for license details.

#ifndef __BRIDGE_REGISTRY_H
#define __BRIDGE_REGISTRY_H

#include "core/config.h"
#include "core/widget.h"

#include <cassert>

#include <memory>
#include <unordered_map>
#include <vector>

class FASEDMemoryTimingModel;
class bridge_driver_t;
class widget_t;
class StreamEngine;

/**
 * Unique class responsible for the creating and ownership of all bridges.
 *
 * The bridges of a design are registered with the registry through the
 * `add_widget` methods for them to be retrieved later through the appropriate
 * getters.
 */
class widget_registry_t final {
public:
  widget_registry_t();
  ~widget_registry_t();

  /**
   * Return a list of pointers to all bridges of a type.
   *
   * @tparam T Type of bridge to fetch.
   * @return List of non-owning pointers to bridges.
   */
  template <typename T>
  std::vector<T *> get_bridges() {
    std::vector<T *> bridge_list;
    for (auto &bridge : widgets[&T::KIND]) {
      bridge_list.push_back(static_cast<T *>(bridge.get()));
    }
    return bridge_list;
  }

  /**
   * Return a widget of a particular kind which has a single instance.
   *
   * This function should only be used with widgets that have a unique instance.
   * If multiple widgets of the same kind were registered or the widget does
   * not exist, the function fails.
   *
   * @tparam T Type of the widget to fetch
   * @return Reference to the widget.
   */
  template <typename T>
  T &get_widget() {
    auto *widget = get_widget_opt<T>();
    assert(widget && "cannot find widget");
    return *widget;
  }

  /**
   * Return a widget of a particular kind, if it has a single instance.
   *
   * @tparam T Type of the widget to fetch
   * @return Pointer to the widget instance.
   */
  template <typename T>
  T *get_widget_opt() {
    auto it = widgets.find(&T::KIND);
    if (it == widgets.end() || it->second.size() != 1)
      return nullptr;
    return static_cast<T *>(it->second[0].get());
  }

  /**
   * Returns all bridges in the deterministic order of their construction.
   *
   * Hash map traversals are not deterministic, especially if keys are pointers.
   * To be able to iterate over bridges in a stable order across different runs,
   * the bridges are also stored in a list in the order they are built.
   */
  const std::vector<bridge_driver_t *> &get_all_bridges() {
    return all_bridges;
  }

  /**
   * Returns a pointer to the stream engine widget, if one exists.
   */
  StreamEngine *get_stream_engine() { return stream_engine.get(); }

  void add_widget(widget_t *widget);
  void add_widget(bridge_driver_t *widget);
  void add_widget(StreamEngine *widget);

private:
  // Mapping from bridge kinds to the list of bridges of that kind.
  using widget_list_t = std::vector<std::unique_ptr<widget_t>>;
  std::unordered_map<const void *, widget_list_t> widgets;

  /**
   * Widget implementing CPU-managed streams.
   */
  std::unique_ptr<StreamEngine> stream_engine;

  /**
   * List of all bridges, maintained in a deterministic order.
   */
  std::vector<bridge_driver_t *> all_bridges;
};

#endif // __BRIDGE_REGISTRY_H
