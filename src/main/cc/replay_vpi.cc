#include "replay_vpi.h"
#include "vcs_main.h"

void replay_vpi_t::init(int argc, char** argv) {
  host = context_t::current();
  target_args_t *targs = new target_args_t(argc, argv);
  target.init(target_thread, targs);
  replay_t::init(argc, argv);
  target.switch_to();
}

int replay_vpi_t::finish() {
  int exitcode = replay_t::finish();
  target.switch_to();
  return exitcode;
}

void replay_vpi_t::probe_signals() {
  // traverse testbench first
  vpiHandle replay_handle = vpi_scan(vpi_iterate(vpiModule, NULL));
  vpiHandle reg_iter = vpi_iterate(vpiReg, replay_handle);
  vpiHandle net_iter = vpi_iterate(vpiNet, replay_handle);
  while (vpiHandle reg_handle = vpi_scan(reg_iter)) {
    std::string regname = vpi_get_str(vpiName, reg_handle);
    if ((regname.find("io_") == 0 && regname.find("_delay") != 0) || regname.find("reset") == 0)
      add_signal(reg_handle, regname);
  }
  while (vpiHandle net_handle = vpi_scan(net_iter)) {
    std::string netname = vpi_get_str(vpiName, net_handle);
    if (netname.find("io_") == 0 && netname.find("_delay") != 0)
      add_signal(net_handle, netname);
  }

  vpiHandle syscall_handle = vpi_handle(vpiSysTfCall, NULL);
  vpiHandle arg_iter = vpi_iterate(vpiArgument, syscall_handle);
  vpiHandle top_handle = vpi_scan(arg_iter);
  std::queue<vpiHandle> modules;
  size_t offset = std::string(vpi_get_str(vpiFullName, top_handle)).find(".") + 1;

  // Start from the top module
  modules.push(top_handle);

  while (!modules.empty()) {
    vpiHandle mod_handle = modules.front();
    modules.pop();

    std::string modname = std::string(vpi_get_str(vpiFullName, mod_handle)).substr(offset);
    // Iterate its nets
    vpiHandle net_iter = vpi_iterate(vpiNet, mod_handle);
    while (vpiHandle net_handle = vpi_scan(net_iter)) {
      std::string netname = vpi_get_str(vpiName, net_handle);
      if (netname.find("io_") == 0) {
        std::string netpath = modname + "." + netname;
        add_signal(net_handle, netpath);
      }
    }

    // Iterate its regs
    vpiHandle reg_iter = vpi_iterate(vpiReg, mod_handle);
    while (vpiHandle reg_handle = vpi_scan(reg_iter)) {
      std::string regname = vpi_get_str(vpiName, reg_handle);
      std::string regpath = modname + "." + regname;
      add_signal(reg_handle, regpath);
    }

    // Iterate its mems
    vpiHandle mem_iter = vpi_iterate(vpiRegArray, mod_handle);
    while (vpiHandle mem_handle = vpi_scan(mem_iter)) {
      vpiHandle elm_iter = vpi_iterate(vpiReg, mem_handle);
      while (vpiHandle elm_handle = vpi_scan(elm_iter)) {
        std::string elmname = vpi_get_str(vpiName, elm_handle);
        std::string elmpath = modname + "." + elmname;
        add_signal(elm_handle, elmpath);
      }
    }

    // Find DFF
    vpiHandle udp_iter = vpi_iterate(vpiPrimitive, mod_handle);
    while (vpiHandle udp_handle = vpi_scan(udp_iter)) {
      if (vpi_get(vpiPrimType, udp_handle) == vpiSeqPrim) {
        add_signal(udp_handle, modname);
      }
    }

    vpiHandle sub_iter = vpi_iterate(vpiModule, mod_handle);
    while (vpiHandle sub_handle = vpi_scan(sub_iter)) {
      modules.push(sub_handle);
    }
  }
}

void replay_vpi_t::put_value(vpiHandle& sig, std::string& value, PLI_INT32 flag) {
  s_vpi_value value_s;
  s_vpi_time time_s;
  value_s.format    = vpiHexStrVal;
  value_s.value.str = (PLI_BYTE8*) value.c_str();
  time_s.type       = vpiScaledRealTime;
  time_s.real       = flag == vpiTransportDelay ? 0.1 : 0.0;
  vpi_put_value(sig, &value_s, &time_s, flag);
}

void replay_vpi_t::get_value(vpiHandle& sig, std::string& value) {
  s_vpi_value value_s;
  value_s.format = vpiHexStrVal;
  vpi_get_value(sig, &value_s);
  value = value_s.value.str;
}

void replay_vpi_t::put_value(vpiHandle& sig, biguint_t* data, PUT_VALUE_TYPE type) {
  std::string value = data->str();
  PLI_INT32 flag;
  switch(type) {
    case PUT_POKE: flag = vpiInertialDelay; break;
    case PUT_LOAD: flag = vpiTransportDelay; break;
    case PUT_FORCE: flag = vpiForceFlag; forces.push(sig); break;
  }
  put_value(sig, value, flag);
}

biguint_t replay_vpi_t::get_value(vpiHandle& sig) {
  std::string value;
  get_value(sig, value);
  return biguint_t(value.c_str());
}

void replay_vpi_t::take_steps(size_t n) {
  for (size_t i = 0 ; i < n ; i++)
    target.switch_to();
}

void replay_vpi_t::tick() {
  host->switch_to();
  vpiHandle syscall_handle = vpi_handle(vpiSysTfCall, NULL);
  vpiHandle arg_iter = vpi_iterate(vpiArgument, syscall_handle);
  vpiHandle exit_handle = vpi_scan(arg_iter);
  vpiHandle exitcode_handle = vpi_scan(arg_iter);
  s_vpi_value vexit, vexitcode;
  vexit.format = vpiIntVal;
  vexit.value.integer = done();
  vexitcode.format = vpiIntVal;
  vexitcode.value.integer = exitcode();
  vpi_put_value(exit_handle, &vexit, NULL, vpiNoDelay);
  vpi_put_value(exitcode_handle, &vexitcode, NULL, vpiNoDelay);
}

static replay_vpi_t* replay = NULL;

extern "C" {

PLI_INT32 init_sigs_calltf(PLI_BYTE8 *user_data) {
  replay->probe_signals();
  return 0;
}

PLI_INT32 tick_calltf(PLI_BYTE8 *user_data) {
  replay->tick();
  return 0;
}

PLI_INT32 sim_end_cb(p_cb_data cb_data) {
  replay->tick();
  return 0;
}

PLI_INT32 tick_compiletf(PLI_BYTE8 *user_data) {
  s_cb_data data_s;
  data_s.reason    = cbEndOfSimulation;
  data_s.cb_rtn    = sim_end_cb;
  data_s.obj       = NULL;
  data_s.time      = NULL;
  data_s.value     = NULL;
  data_s.user_data = NULL;
  vpi_free_object(vpi_register_cb(&data_s));
}

int main(int argc, char** argv) {
  replay = new replay_vpi_t;
  replay->init(argc, argv);
  replay->replay();
  int exitcode = replay->finish();
  delete replay;
  return exitcode;
}

}

