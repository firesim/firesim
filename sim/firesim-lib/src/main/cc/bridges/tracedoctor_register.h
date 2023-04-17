#ifndef TRACEDOCTOR_REGISTER_H__
#define TRACEDOCTOR_REGISTER_H__

#include "tracedoctor_worker.h"
#include "tracedoctor_example.h"

#include <string>
#include <memory>
#include <functional>
#include <memory>

#define __ADD_TRACEDOCTOR_WORKER(__name, __class, ...)                       \
  {                                                                          \
    __name,                                                                  \
    [](std::vector<std::string> const &args, struct traceInfo const &info){  \
      return std::unique_ptr<tracedoctor_worker>(new __class(__VA_ARGS__));  \
    }                                                                        \
  },

#define ADD_TRACEDOCTOR_WORKER(__name, __class) __ADD_TRACEDOCTOR_WORKER(__name, __class, args, info)

typedef std::map<std::string, std::function<std::unique_ptr<tracedoctor_worker>(std::vector<std::string> const &, struct traceInfo const &)>> tracedoctor_register_t;

// This is the worker register. Add your entries in this map to register new workers.
static tracedoctor_register_t const tracedoctor_register = {
  ADD_TRACEDOCTOR_WORKER("dummy", tracedoctor_dummy)
  ADD_TRACEDOCTOR_WORKER("filer", tracedoctor_filer)
  ADD_TRACEDOCTOR_WORKER("tracerv", tracedoctor_tracerv)
};

// HINT: if the compiler complains about 'expected primary-expressin'
// it most likely couldn't find the tracedoctor worker class you have
// specificed. Make sure you have included the correct header file and
// that the class is spelled correctly.

#endif
