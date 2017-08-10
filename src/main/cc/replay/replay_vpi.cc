#include "replay_vpi.h"
#include "emul/vcs_main.h"

void replay_vpi_t::init(int argc, char** argv) {
  host = midas_context_t::current();
  target_args_t *targs = new target_args_t(argc, argv);
  target.init(target_thread, targs);
  replay_t::init(argc, argv);
  target.switch_to();
}

int replay_vpi_t::finish() {
  target.switch_to();
  return replay_t::finish();
}

void replay_vpi_t::add_signal(vpiHandle& sig_handle, std::string& wire) {
  size_t id = replay_data.signals.size();
  replay_data.signals.push_back(sig_handle);
  replay_data.signal_map[wire] = id;
}

void replay_vpi_t::probe_bits(vpiHandle& sig_handle, std::string& sigpath, std::string& modname) {
  if (gate_level()) {
    if (vpi_get(vpiSize, sig_handle) == 1) {
      std::string bitpath = sigpath + "[0]";
      add_signal(sig_handle, bitpath);
    } else {
      vpiHandle bit_iter = vpi_iterate(vpiBit, sig_handle);
      while (vpiHandle bit_handle = vpi_scan(bit_iter)) {
        std::string bitname = vpi_get_str(vpiName, bit_handle);
        std::string bitpath = modname + "." + bitname;
        add_signal(bit_handle, bitpath);
      }
    }
  }
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

    if (!vpi_scan(vpi_iterate(vpiPrimitive, mod_handle))) { // Not a gate?
      // Iterate its ports
      vpiHandle net_iter = vpi_iterate(vpiNet, mod_handle);
      while (vpiHandle net_handle = vpi_scan(net_iter)) {
        std::string netname = vpi_get_str(vpiName, net_handle);
        std::string netpath = modname + "." + netname;
        add_signal(net_handle, netpath);
        probe_bits(net_handle, netpath, modname);
      }
    }

    // Iterate its regs
    vpiHandle reg_iter = vpi_iterate(vpiReg, mod_handle);
    while (vpiHandle reg_handle = vpi_scan(reg_iter)) {
      std::string regname = vpi_get_str(vpiName, reg_handle);
      std::string regpath = modname + "." + regname;
      add_signal(reg_handle, regpath);
      probe_bits(reg_handle, regpath, modname);
    }

    // Iterate its mems
    vpiHandle mem_iter = vpi_iterate(vpiRegArray, mod_handle);
    while (vpiHandle mem_handle = vpi_scan(mem_iter)) {
      vpiHandle elm_iter = vpi_iterate(vpiReg, mem_handle);
      while (vpiHandle elm_handle = vpi_scan(elm_iter)) {
        std::string elmname = vpi_get_str(vpiName, elm_handle);
        std::string elmpath = modname + "." + elmname;
        add_signal(elm_handle, elmpath);
        probe_bits(elm_handle, elmpath, modname);
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
  // s_vpi_time time_s;
  value_s.format    = vpiHexStrVal;
  value_s.value.str = (PLI_BYTE8*) value.c_str();
  // time_s.type       = vpiScaledRealTime;
  // time_s.real       = 0.0;
  vpi_put_value(sig, &value_s, /*&time_s*/ NULL, flag);
}

void replay_vpi_t::get_value(vpiHandle& sig, std::string& value) {
  s_vpi_value value_s;
  value_s.format = vpiHexStrVal;
  vpi_get_value(sig, &value_s);
  value = value_s.value.str;
}

void replay_vpi_t::put_value(vpiHandle& sig, mpz_t& data, PUT_VALUE_TYPE type) {
  PLI_INT32 flag;
  switch(type) {
    case PUT_DEPOSIT: flag = vpiNoDelay; break;
    case PUT_FORCE: flag = vpiForceFlag; forces.push(sig); break;
  }
  size_t value_size;
  uint32_t* value = (uint32_t*)mpz_export(NULL, &value_size, -1, sizeof(uint32_t), 0, 0, data);
  size_t signal_size = ((vpi_get(vpiSize, sig) - 1) / 32) + 1;
  s_vpi_value  value_s;
  s_vpi_vecval vecval_s[signal_size];
  value_s.format       = vpiVectorVal;
  value_s.value.vector = vecval_s;
  for (size_t i = 0 ; i < signal_size ; i++) {
    value_s.value.vector[i].aval = i < value_size ? value[i] : 0;
    value_s.value.vector[i].bval = 0;
  }
  vpi_put_value(sig, &value_s, NULL, flag);
}

void replay_vpi_t::get_value(vpiHandle& sig, mpz_t& data) {
  size_t signal_size = ((vpi_get(vpiSize, sig) - 1) / 32) + 1;
  s_vpi_value  value_s;
  s_vpi_vecval vecval_s[signal_size];
  value_s.format       = vpiVectorVal;
  value_s.value.vector = vecval_s;
  vpi_get_value(sig, &value_s);

  uint32_t value[signal_size];
  for (size_t i = 0 ; i < signal_size ; i++) {
    value[i] = value_s.value.vector[i].aval;
  }
  mpz_import(data, signal_size, -1, sizeof(uint32_t), 0, 0, value);
}

void replay_vpi_t::take_steps(size_t n) {
  for (size_t i = 0 ; i < n ; i++)
    target.switch_to();
}

void replay_vpi_t::tick() {
  while(!forces.empty()) {
    vpi_put_value(forces.front(), NULL, NULL, vpiReleaseFlag);
    forces.pop();
  }
  host->switch_to();
  vpiHandle syscall_handle = vpi_handle(vpiSysTfCall, NULL);
  vpiHandle arg_iter = vpi_iterate(vpiArgument, syscall_handle);
  vpiHandle exit_handle = vpi_scan(arg_iter);
  s_vpi_value vexit;
  vexit.format = vpiIntVal;
  vexit.value.integer = done();
  vpi_put_value(exit_handle, &vexit, NULL, vpiNoDelay);
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

