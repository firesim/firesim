#ifndef TRACEDOCTOR_REGISTER_H__
#define TRACEDOCTOR_REGISTER_H__

#include "tracedoctor_worker.h"
#include "tracedoctor_example.h"

#include <string>
#include <memory>

// This is the worker register. Add your entries in this map to register new workers.

static std::map<std::string,std::function<std::shared_ptr<tracedoctor_worker>(std::vector<std::string> &, struct traceInfo &)>> const tracedoctor_register = {
  {"dummy", [](std::vector<std::string> &args, struct traceInfo &info){
      return std::make_shared<tracedoctor_worker>("Dummy", args, info, TDWORKER_NO_FILES);
    }},
  {"filer", [](std::vector<std::string> &args, struct traceInfo &info){
      return std::make_shared<tracedoctor_filedumper>(args, info);
    }},
  {"tracerv",[](std::vector<std::string> &args, struct traceInfo &info){
      return std::make_shared<tracedoctor_tracerv>(args, info);
    }},
};
#endif
