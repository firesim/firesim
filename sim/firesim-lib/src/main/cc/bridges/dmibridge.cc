// See LICENSE for license details

#include "dmibridge.h"
#include "bridges/loadmem.h"
#include "core/simif.h"
#include "fesvr/firesim_dtm.h"

#include <cassert>
#include <gmp.h>

char dmibridge_t::KIND;

// TODO: Support loadmem
dmibridge_t::dmibridge_t(simif_t &simif,
                         loadmem_t &loadmem_widget,
                         const DMIBRIDGEMODULE_struct &mmio_addrs,
                         int dmino,
                         const std::vector<std::string> &args,
                         bool has_mem,
                         int64_t mem_host_offset)
    : bridge_driver_t(simif, &KIND), mmio_addrs(mmio_addrs),
      has_mem(false), mem_host_offset(mem_host_offset) {

  std::string num_equals = std::to_string(dmino) + std::string("=");
  std::string prog_arg = std::string("+prog") + num_equals;
  std::vector<std::string> args_vec;
  args_vec.push_back("firesim_dtm");

  // This particular selection is vestigial. You may change it freely.
  step_size = 2004765L;
  for (auto &arg : args) {
    if (arg.find("+fesvr-step-size=") == 0) {
      step_size = atoi(arg.c_str() + 17);
    }
    if (arg.find(prog_arg) == 0) {
      std::string clean_target_args =
          const_cast<char *>(arg.c_str()) + prog_arg.length();

      std::istringstream ss(clean_target_args);
      std::string token;
      while (std::getline(ss, token, ' ')) {
        args_vec.push_back(token);
      }
    } else if (arg.find(std::string("+prog")) == 0) {
      // Eliminate arguments for other fesvrs
    } else {
      args_vec.push_back(arg);
    }
  }

  int argc_count = args_vec.size() - 1;
  dmi_argv = new char *[args_vec.size()];
  for (size_t i = 0; i < args_vec.size(); ++i) {
    dmi_argv[i] = new char[args_vec[i].size() + 1];
    std::strcpy(dmi_argv[i], args_vec[i].c_str());
  }

  // debug for command line arguments
  printf("command line for program %d. argc=%d:\n", dmino, argc_count);
  for (int i = 0; i < argc_count; i++) {
    printf("%s ", dmi_argv[i + 1]);
  }
  printf("\n");

  dmi_argc = argc_count + 1;
}

dmibridge_t::~dmibridge_t() {
  if (fesvr)
    delete fesvr;
  if (dmi_argv) {
    for (int i = 0; i < dmi_argc; ++i) {
      if (dmi_argv[i])
        delete[] dmi_argv[i];
    }
    delete[] dmi_argv;
  }
}

void dmibridge_t::init() {
  // `ucontext` used by dmi cannot be created in one thread and resumed in
  // another. To ensure that the dmi process is on the correct thread, it is
  // built here, as the bridge constructor may be invoked from a thread other
  // than the one it will run on later in meta-simulations.
  fesvr = new firesim_dtm_t(dmi_argc, dmi_argv, has_mem);
  write(mmio_addrs.step_size, step_size);
  go();
}

void dmibridge_t::go() { write(mmio_addrs.start, 1); }

void dmibridge_t::tick() {
  // First, check to see step_size tokens have been enqueued
  if (!read(mmio_addrs.done))
    return;

  // req from the host, resp from the target
  // in(to) the target, out from the target

  auto resp_valid = read(mmio_addrs.out_valid);
  dtm_t::resp out_resp;
  if (resp_valid) {
    out_resp.resp = read(mmio_addrs.out_bits_resp);
    out_resp.data = read(mmio_addrs.out_bits_data);
    printf("DEBUG: Resp read: resp(0x%x) data(0x%x)\n", out_resp.resp, out_resp.data);
    write(mmio_addrs.out_ready, 1);
  }
  fesvr->tick(
    read(mmio_addrs.in_ready),
    resp_valid,
    out_resp
  );

  if (!terminate()) {
    if (fesvr->req_valid() && read(mmio_addrs.in_ready)) {
      dtm_t::req in_req = fesvr->req_bits();
      printf("DEBUG: Req sent: addr(0x%x) op(0x%x) data(0x%x)\n", in_req.addr, in_req.op, in_req.data);
      write(mmio_addrs.in_bits_addr, in_req.addr);
      write(mmio_addrs.in_bits_op, in_req.op);
      write(mmio_addrs.in_bits_data, in_req.data);
      write(mmio_addrs.in_valid, 1);
    }

    // Move forward step_size iterations
    go();
  }
}

bool dmibridge_t::terminate() { return fesvr->done(); }
int dmibridge_t::exit_code() { return fesvr->exit_code(); }
