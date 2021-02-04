#ifdef  CORETEMPERATUREBRIDGEMODULE_struct_guard

#include "bridges/coretemp.h"

coretemp_t::coretemp_t(simif_t* sim, CORETEMPERATUREBRIDGEMODULE_struct* mmio_addrs, std::vector<std::string> &args):
        bridge_driver_t(sim), mmio_addrs(mmio_addrs) {
    output_file.open("temp-stats.csv", std::ofstream::out);
    if(!output_file.is_open()) {
      throw std::runtime_error("Could not open temp data output file\n");
    }
};


void coretemp_t::init() {
      fprintf(stderr, "Core Temperature Model Initializing\n");
      write(this->mmio_addrs->update_interval, update_interval);
      write(this->mmio_addrs->temperature, (int)(last_temp * 2));
      write(this->mmio_addrs->done_init, 1);
      output_file << "Cycle Count,Tile Cycle Count,Instructions Retired,Temperature"  << std::endl;
      output_file << "0, 0, 0, " << last_temp << std::endl;
}

void coretemp_t::tick() {
    if (read(this->mmio_addrs->idle)) {
        uint32_t cur_insns_ret = read(this->mmio_addrs->instructions_retired);
        uint32_t cur_cycle_count = read(this->mmio_addrs->tile_cycle_count);
        uint32_t insns_ret_delta = cur_insns_ret - last_insns_ret;
        uint32_t cycle_count_delta = cur_cycle_count - last_cycle_count;
        last_insns_ret = cur_insns_ret;
        last_cycle_count = cur_cycle_count;
        //fprintf(stderr, "Insns Retired Delta: %d: Cycle Count Delta: %d\n", insns_ret_delta, cycle_count_delta);

        double cycle_ratio = cycle_count_delta / update_interval;
        double voltage = 0.5 + cycle_ratio * 0.5;
        double dynamic_power = dynamic_coefficient * voltage * voltage * insns_ret_delta;
        double static_power  = static_coefficient * voltage * update_interval;

        double core_heat = heating_coefficient * (dynamic_power + static_power);
        double cooling = cooling_coefficient * (ambient_temp - last_temp) * update_interval;
        double new_temp = last_temp + core_heat + cooling;
        assert(new_temp > 0);
        assert(new_temp < 256);
        //fprintf(stderr, "Old Temperature: %f: New Temperature: %f\n", last_temp, new_temp);
        write(this->mmio_addrs->temperature, (int) (new_temp * 2));
        write(this->mmio_addrs->reset_count, 1);
        last_temp = new_temp;


        total_insns_ret += insns_ret_delta;
        total_cycle_count += cycle_count_delta;
        total_local_cycle_count += update_interval;
        output_file << total_local_cycle_count << "," << total_cycle_count << ", " << total_insns_ret << "," << last_temp << std::endl;
    }
}
#endif // CORETEMPERATUREBRIDGEMODULE_struct_guard
