#include "loadmem_m.h"

void loadmem_m::load_mem(std::string filename) {
/*
  fprintf(stdout, "[loadmem] start loading\n");
  std::ifstream file(filename.c_str());
  if (!file) {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(EXIT_FAILURE);
  }
  const size_t chunk = MEM_DATA_BITS / 4;
  size_t addr = 0;
  std::string line;
  mpz_t data;
  mpz_init(data);
  while (std::getline(file, line)) {
    assert(line.length() % chunk == 0);
    for (int j = line.length() - chunk ; j >= 0 ; j -= chunk) {
      mpz_set_str(data, line.substr(j, chunk).c_str(), 16);
      write_mem(addr, data);
      addr += chunk / 2;
    }
  }
  file.close();
  fprintf(stdout, "[loadmem] done\n");
*/
}


void loadmem_m::read_mem(size_t addr, mpz_t& value) {
  write("R_ADDRESS_H", addr >> 32);
  write("R_ADDRESS_L", addr & ((1ULL << 32) - 1));
  const size_t size = MEM_DATA_CHUNK;
  data_t data[size];
  for (size_t i = 0 ; i < size ; i++) {
    data[i] = read("R_DATA");
  }
  mpz_import(value, size, -1, sizeof(data_t), 0, 0, data);
}

void loadmem_m::write_mem(size_t addr, mpz_t& value) {
  write("W_ADDRESS_H", addr >> 32);
  write("W_ADDRESS_L", addr & ((1ULL << 32) - 1));
  size_t size;
  data_t* data = (data_t*)mpz_export(NULL, &size, -1, sizeof(data_t), 0, 0, value);
  for (size_t i = 0 ; i < MEM_DATA_CHUNK ; i++) {
    write("W_DATA", i < size ? data[i] : 0);
  }
}

