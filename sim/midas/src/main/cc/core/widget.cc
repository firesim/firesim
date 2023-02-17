// See LICENSE for license details.

#include "widget.h"

widget_t::widget_t(simif_t &simif, const void *kind)
    : simif(simif), kind(kind) {}

widget_t::~widget_t() = default;
