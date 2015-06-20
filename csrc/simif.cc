#include "simif.h"
#include <fstream>
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

simif_t::simif_t(std::vector<std::string> args, std::string _prefix,  bool _log): log(_log)
{
  // initialization
  ok = true;
  t = 0;
  fail_t = 0;
  prefix = _prefix;

  in_num = 0;
  out_num = 0;

  srand(time(NULL));

  // Read mapping files
  read_map(prefix + ".in.map");
  read_map(prefix + ".out.map");

  size_t i;
  for (i = 0 ; i < args.size() ; i++) {
    if (args[i].length() && args[i][0] != '-' && args[i][0] != '+')
      break;
  }
  hargs.insert(hargs.begin(), args.begin(), args.begin() + i);
  targs.insert(targs.begin(), args.begin() + i, args.end());

  for (auto &arg: hargs) {
    if (arg.find("+sample-num=") == 0) {
      // sample_num = atoi(arg.c_str()+12);
    } else if (arg.find("+loadmem=") == 0) {
      loadmem = arg.c_str()+9;
    }
  }
}

simif_t::~simif_t() { 
  fprintf(stdout, "[%s] %s Test", ok ? "PASS" : "FAIL", prefix.c_str());
  if (!ok) {
    fprintf(stdout, " at cycle %lu", fail_t);
  }
  fprintf(stdout, "\n");
}

void simif_t::read_map(std::string filename) {
  std::ifstream file(filename.c_str());
  std::string line;
  if (file) {
    while (getline(file, line)) {
      std::istringstream iss(line);
      std::string path;
      size_t id, width;
      iss >> path >> id >> width;
      if (filename.find(".in.map") != std::string::npos) {
        in_num++;
        in_map[path] = id;
        in_widths[id] = width;
      } else if (filename.find(".out.map") != std::string::npos) {
        out_num++;
        out_map[path] = id;
        out_widths[id] = width;
      } 
    }
  } else {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(0);
  }
  file.close();
}

void simif_t::init() {
  peek_map.clear();
  for (size_t id = 0 ; id < out_num ; id++) {
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
    for (size_t id = 0 ; id < in_num ; id++) {
      poke_channel(id, poke_map.find(id) != poke_map.end() ? poke_map[id] : 0);
    }
    peek_map.clear();
    for (size_t id = 0 ; id < out_num ; id++) {
      peek_map[id] = peek_channel(id);
    }
  }
  t += n;
}
