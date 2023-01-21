// See LICENSE for license details.

#ifndef __WIDGET_H
#define __WIDGET_H

#include <cstdlib>

class widget_t;
class widget_registry_t;
class simif_t;

template <class T>
bool widget_isa(widget_t *widget);

/**
 * Base class of all drivers interfacing with a widget on the simulation.
 *
 * Widgets are all registered in the widget registry and are constructed
 * automatically based on information retrieved from the target.
 *
 * A per-class kind uniquely identifies each widget types and allows them
 * to be classified and retrieved from the registry.
 */
class widget_t {
public:
  /**
   * Constructor for widgets.
   *
   * @param simif  Reference to the interface providing MMIO access.
   * @param kind A unique token to identify the widget kind.
   */
  widget_t(simif_t &simif, const void *kind);

  virtual ~widget_t();

protected:
  /**
   * Reference to the MMIO interface.
   */
  simif_t &simif;

private:
  /**
   * Unique token identifying the widget kind.
   */
  const void *kind;

  // The type check must access the kind.
  template <class T>
  friend T *widget_isa(widget_t *widget);

  // Bridge registry must access the kind.
  friend class widget_registry_t;
};

/**
 * Type check of widgets.
 *
 * Uses a custom type information encoding to identify widgets without relying
 * on C++ RTTI. This mechanism is identical to that used by LLVM to register
 * and identify passes, even ones loaded dynamically through shared libraries.
 *
 * Each class provides a `KIND` field, which is an uninitialized item.
 * Constructors set the `kind` to a pointer to this field, which is guaranteed
 * to be unique across the application, allowing widgets of different kinds to
 * be distinguished without involving RTTI.
 */
template <class T>
bool widget_isa(widget_t *widget) {
  return widget->kind == &T::KIND;
}

/**
 * Dynamic cast between widgets, return nullptr on mismatch.
 */
template <class T>
T *widget_dyn_cast(widget_t *widget) {
  if (!widget_isa<T>(widget))
    return nullptr;
  return static_cast<T *>(widget);
}

/**
 * Cast between widgets, aborting on mismatched types.
 */
template <class T>
T *widget_cast(widget_t *widget) {
  if (auto *target = widget_dyn_cast<T>(widget))
    return target;
  abort();
}

#endif // __WIDGET_H
