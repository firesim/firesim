// See LICENSE for license details.

#include "bridges/fased_memory_timing_model.h"
#include "core/config.h"

#include <filesystem>
#include <iostream>
#include <stdexcept>

namespace {

const TargetConfig config{};

void require(bool condition, const std::string &message) {
  if (!condition)
    throw std::runtime_error(message);
}

// A register-level device model. The test links the production
// driver and exercises it through simif_t, without an FPGA or generated RTL.
class RegisterDevice : public simif_t {
public:
  explicit RegisterDevice(unsigned index,
                          bool histogram = false,
                          bool ranges = false)
      : simif_t(config), index(index) {
    readable.emplace_back("readLatency", 0);
    writable = readable;
    const std::vector<std::string> histogram_names{"hostReadLatency",
                                                   "hostWriteLatency",
                                                   "targetReadLatency",
                                                   "targetWriteLatency",
                                                   "ingressReadLatency",
                                                   "ingressWriteLatency",
                                                   "totalReadLatency",
                                                   "totalWriteLatency"};
    for (unsigned i = 0; histogram && i < histogram_names.size(); ++i)
      add_counter(histogram_names[i] + "Hist_", 16 * (i + 1));
    if (ranges) {
      add_counter("readRanges_", 144);
      add_counter("writeRanges_", 160);
      readable.emplace_back("numRanges", 4000);
    }
  }

  void write(size_t address, uint32_t value) override {
    registers[address] = value;
  }

  uint32_t read(size_t address) override {
    if (address == 4000)
      return 4;
    if (address >= 16 && address <= 172 && address % 16 == 12)
      return finished ? index * 100000 + (address / 16 - 1) * 2000 +
                            registers[address - 8]
                      : 0;
    return registers[address];
  }

  AddressMap addresses() const { return AddressMap(readable, writable); }

  bool finished = false;
  std::map<size_t, uint32_t> registers;

private:
  void add_counter(const std::string &prefix, unsigned base) {
    for (const auto &[field, offset] :
         std::vector<std::pair<std::string, unsigned>>{
             {"enable", 0}, {"addr", 4}, {"dataH", 8}, {"dataL", 12}}) {
      readable.emplace_back(prefix + field, base + offset);
      if (offset < 8)
        writable.emplace_back(prefix + field, base + offset);
    }
  }

  unsigned index;
  std::vector<std::pair<std::string, uint32_t>> readable, writable;
};

std::string first_data_row(const std::string &path) {
  std::ifstream input(path);
  require(input.good(), "Missing output: " + path);
  std::string line;
  std::getline(input, line);
  std::getline(input, line);
  return line;
}

void lifetime(bool histogram) {
  RegisterDevice device(0, histogram, !histogram);
  // The constructor's AddressMap argument expires at this semicolon.
  FASEDMemoryTimingModel model(
      device, device.addresses(), 0, {}, "stats.csv", 4096);
  model.init();
  device.finished = true;
  model.finish();
  if (histogram)
    require(first_data_row("latency_histogram.csv").rfind("0,2000,", 0) == 0,
            "Histogram values differ from device counters");
  else
    require(first_data_row("range_counters.csv") == "0,16000,18000,",
            "Range values differ from device counters");
}

void model_index() {
  RegisterDevice one(1), ten(10);
  const std::vector<std::string> args{"+mm_readLatency_1=17",
                                      "+mm_readLatency_10=29"};
  FASEDMemoryTimingModel first(one, one.addresses(), 1, args, "one.csv", 4096);
  FASEDMemoryTimingModel second(
      ten, ten.addresses(), 10, args, "ten.csv", 4096);
  first.init();
  second.init();
  require(one.registers[0] == 17 && ten.registers[0] == 29,
          "Model 1 consumed model 10's argument");
}

void numeric(const std::string &value, bool valid, uint32_t expected = 0) {
  RegisterDevice device(0);
  bool rejected = false;
  try {
    FASEDMemoryTimingModel model(device,
                                 device.addresses(),
                                 0,
                                 {"+mm_readLatency_0=" + value},
                                 "stats.csv",
                                 4096);
    model.init();
  } catch (const std::exception &) {
    rejected = true;
  }
  require(rejected != valid,
          "Unexpected acceptance of configuration '" + value + "'");
  if (valid)
    require(device.registers[0] == expected, "Incorrect configuration value");
}

void output_isolation() {
  for (unsigned index : {0u, 1u, 10u}) {
    RegisterDevice device(index, true, true);
    // Keep the argument alive to isolate output naming from the lifetime bug.
    auto addresses = device.addresses();
    FASEDMemoryTimingModel model(device,
                                 std::move(addresses),
                                 index,
                                 {},
                                 "stats" + std::to_string(index) + ".csv",
                                 4096);
    model.init();
    device.finished = true;
    model.finish();
  }
  for (unsigned index : {0u, 1u, 10u}) {
    const std::string suffix = index ? std::to_string(index) : "";
    require(first_data_row("latency_histogram" + suffix + ".csv")
                    .rfind(std::to_string(index * 100000) + ",", 0) == 0,
            "A model overwrote another model's histogram");
    require(first_data_row("range_counters" + suffix + ".csv")
                    .rfind("0," + std::to_string(index * 100000 + 16000) + ",",
                           0) == 0,
            "A model overwrote another model's ranges");
  }
}

void defaults() {
  RegisterDevice device(1);
  FASEDMemoryTimingModel model(
      device,
      device.addresses(),
      1,
      {"+mm_useHardwareDefaultRuntimeSettings_1", "+mm_readLatency_10=29"},
      "stats.csv",
      4096);
  model.init();
  require(device.registers.empty(),
          "Unrelated model configuration was applied");
}

} // namespace

int main(int argc, char **argv) {
  try {
    require(argc == 2, "Expected one test case name");
    const std::string name = argv[1];
    if (name == "histogram-lifetime")
      lifetime(true);
    else if (name == "range-lifetime")
      lifetime(false);
    else if (name == "model-index")
      model_index();
    else if (name == "output-isolation")
      output_isolation();
    else if (name == "defaults")
      defaults();
    else if (name == "unsigned32")
      numeric("4294967295", true, UINT32_MAX);
    else if (name == "hex32")
      numeric("0xffffffff", true, UINT32_MAX);
    else if (name == "decimal")
      numeric("00017", true, 17);
    else if (name == "negative")
      numeric("-1", false);
    else if (name == "overflow")
      numeric("4294967296", false);
    else if (name == "trailing-garbage")
      numeric("12oops", false);
    else if (name == "empty")
      numeric("", false);
    else
      throw std::runtime_error("Unknown test case: " + name);
    std::cout << "PASS " << name << '\n';
    return 0;
  } catch (const std::exception &error) {
    std::cerr << "FAIL " << error.what() << '\n';
    return 1;
  }
}
