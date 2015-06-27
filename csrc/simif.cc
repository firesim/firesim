#include "simif.h"
#include <fstream>
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

simif_t::simif_t(std::vector<std::string> args, std::string _prefix,  bool _log): prefix(_prefix), log(_log)
{
  ok = true;
  t = 0;
  fail_t = 0;
  srand(time(NULL));

  size_t i;
  for (i = 0 ; i < args.size() ; i++) {
    if (args[i].length() && args[i][0] != '-' && args[i][0] != '+')
      break;
  }
  hargs.insert(hargs.begin(), args.begin(), args.begin() + i);
  targs.insert(targs.begin(), args.begin() + i, args.end());

  // Read mapping files
  read_map(prefix+".map");
}

simif_t::~simif_t() { 
  fprintf(stdout, "[%s] %s Test", ok ? "PASS" : "FAIL", prefix.c_str());
  if (!ok) { fprintf(stdout, " at cycle %lu", fail_t); }
  fprintf(stdout, "\n");
}

void simif_t::read_map(std::string filename) {
  enum map_type { IoIn, IoOut, MemIn, MemOut };
  std::ifstream file(filename.c_str());
  std::string line;
  if (file) {
    while (getline(file, line)) {
      std::istringstream iss(line);
      std::string path;
      size_t type, id, width;
      iss >> type >> path >> id >> width;
      switch (static_cast<map_type>(type)) {
        case IoIn:
          in_map[path] = id;
          in_widths[id] = width;
          break;
        case IoOut:
          out_map[path] = id;
          out_widths[id] = width;
          break;
        case MemIn:
          req_map[path] = id;
          in_widths[id] = width;
          break;
        case MemOut:
          resp_map[path] = id;
          out_widths[id] = width;
          break;
        default:
          break;
      }
    }
  } else {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(0);
  }
  file.close();
}

void simif_t::load_mem(std::string filename) {
  const size_t step = MEM_DATA_WIDTH >> 2; // -> MEM_DATA_WIDTH / 4
  std::ifstream file(filename.c_str());
  if (file) {
    int i = 0;
    std::string line;
    while (std::getline(file, line)) {
      uint32_t base = (i * line.length()) >> 1;
      size_t offset = 0;
      for (int j = line.length() - step ; j >= 0 ; j -= step) {
        biguint_t data = 0;
        for (int k = 0 ; k < step ; k++) {
          data |= parse_nibble(line[j+k]) << (4*(step-1-k));
        }
        write_mem(base+offset, data);
        offset += step >> 1; // -> step / 2
      }
      i += 1;
    }
  } else {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(1);
  }
  file.close();
}

void simif_t::init() {
  for (auto &arg: hargs) {
    if (arg.find("+sample-num=") == 0) {
      // sample_num = atoi(arg.c_str()+12);
    } else if (arg.find("+loadmem=") == 0) {
      std::string filename = arg.c_str()+9;
      fprintf(stdout, "[loadmem] start loading\n");
      load_mem(filename);
      fprintf(stdout, "[loadmem] done\n");
    }
  }

  peek_map.clear();
  for (iomap_it_t it = out_map.begin() ; it != out_map.end() ; it++) {
    size_t id = it->second;
    peek_map[id] = peek_channel(id);
  }
}

void simif_t::poke_port(std::string path, biguint_t value) {
  assert(in_map.find(path) != in_map.end());
  if (log) fprintf(stdout, "* POKE %s <- %s *\n", path.c_str(), value.str().c_str());
  poke_map[in_map[path]] = value;
}

biguint_t simif_t::peek_port(std::string path) {
  assert(out_map.find(path) != out_map.end());
  assert(peek_map.find(out_map[path]) != peek_map.end());
  biguint_t value = peek_map[out_map[path]];
  if (log) fprintf(stdout, "* PEEK %s -> %s *\n", path.c_str(), value.str().c_str());
  return value;
}

bool simif_t::expect(bool pass, const char *s) {
  if (log) fprintf(stdout, "* %s : %s *\n", s, pass ? "PASS" : "FAIL");
  if (ok && !pass) fail_t = t;
  ok &= pass;
  return pass;
}

bool simif_t::expect_port(std::string path, biguint_t expected) {
  assert(out_map.find(path) != out_map.end());
  assert(peek_map.find(out_map[path]) != peek_map.end());
  biguint_t value = peek_map[out_map[path]];
  bool pass = value == expected;
  std::ostringstream oss;
  oss << "EXPECT " << path << " " << value << " == " << expected;
  return expect(pass, oss.str().c_str());
}

void simif_t::step(size_t n) {
  if (log) fprintf(stdout, "* STEP %u -> %llu *\n", n, (long long) (t + n));
  for (size_t i = 0 ; i < n ; i++) {
    for (iomap_it_t it = in_map.begin() ; it != in_map.end() ; it++) {
      size_t id = it->second;
      poke_channel(id, poke_map.find(id) != poke_map.end() ? poke_map[id] : 0);
    }
    peek_map.clear();
    for (iomap_it_t it = out_map.begin() ; it != out_map.end() ; it++) {
      size_t id = it->second;
      peek_map[id] = peek_channel(id);
    }
  }
  t += n;
}

void simif_t::write_mem(size_t addr, biguint_t data) {
  poke_channel(req_map["mem_req_cmd_addr"], addr >> MEM_BLOCK_OFFSET);
  poke_channel(req_map["mem_req_cmd_tag"], 1);
  poke_channel(req_map["mem_req_data"], data);
}

biguint_t simif_t::read_mem(size_t addr) {
  poke_channel(req_map["mem_req_cmd_addr"], addr >> MEM_BLOCK_OFFSET);
  poke_channel(req_map["mem_req_cmd_tag"], 0);
  assert(peek_channel(resp_map["mem_resp_tag"]) == 0);
  return peek_channel(resp_map["mem_resp_data"]);
}
