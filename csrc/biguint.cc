#include <iomanip>
#include <sstream>
#include <string.h>
#include <assert.h>
#include "biguint.h"

biguint_t::biguint_t(const char* v, size_t base) {
  // we only accepts hex or bin
  // TODO: convert decimals
  char prefix[3]; 
  const char* value;
  strncpy(prefix, v, 2);
  prefix[2] = '\0';
  if (strcmp(prefix, "0x") == 0) {
    value = &v[2];
    base = 16;
  } else if(strcmp(prefix, "0b") == 0) {
    value = &v[2];
    base = 2;
  } else {
    value = v;
  }
  assert(base == 16 || base == 2);
  if (base == 16) 
    init_hex(value); 
  else 
    init_bin(value);
}

biguint_t::biguint_t(const biguint_t& that) {
  size = 0;
  data = NULL;
  copy_biguint(that);
}

std::string biguint_t::str() {
  std::ostringstream oss;
  oss << *this;
  return oss.str();
}

void biguint_t::init(const uint64_t value) {
  init(&value, 1);
}

void biguint_t::init(const uint64_t* value, const size_t s) {
  data = new uint64_t[size = s];
  for (size_t i = 0 ; i < size ; i++) {
    data[i] = value[i];
  }
}

inline void pad_num(char *buf, const char* value, size_t padw) {
  for (size_t i = 0 ; i < padw ; i++) {
    buf[i] = '0';
  }
  strcpy(buf+padw, value);
}

void biguint_t::init_hex(const char* value) {
  int len = strlen(value);
  size = (len+15) / 16;
  data = new uint64_t[size];
  // padding
  char *buf = new char[16*size+1];
  pad_num(buf, value, 16*size-len);
  // converting
  for (size_t i = 0 ; i < size ; i++) {
    char num[17];
    strncpy(num, buf + 16*i, 16);
    num[16] = '\0';
    data[size-1-i] = hex_to_dec(num);
  }
  delete[] buf;
}

void biguint_t::init_bin(const char* value) {
  int len = strlen(value);
  size = (len+63) / 64;
  data = new uint64_t[size];
  // padding
  char *buf = new char[64*size+1];
  pad_num(buf, value, 64*size-len);
  // convering
  for (int i = size - 1 ; i >= 0 ; i--) {
    char num[65];
    strncpy(num, value + 64*i, 64);
    num[64] = '\0';
    data[i] = bin_to_dec(num);
  }
  delete[] buf;
}

void biguint_t::copy_biguint(const biguint_t &that) {
  if (data) delete[] data;
  if (that.data) {
    data = new uint64_t[size = that.size];
    for (size_t i = 0 ; i < size ; i++) {
      data[i] = that.data[i];
    }
  } else {
    data = NULL;
    size = 0;
  }
}

void biguint_t::bit_or(const biguint_t &a, const biguint_t &b) {
  size = std::max(a.size, b.size);
  uint64_t *data = new uint64_t[size];
  for (size_t i = 0 ; i < std::min(a.size, b.size) ; i++) {
    data[i] = a.data[i] | b.data[i];
  }
  for (size_t i = a.size ; i < size ; i++) {
    data[i] = b.data[i];
  }
  for (size_t i = b.size ; i < size ; i++) {
    data[i] = a.data[i];
  }
  if (this->data) delete[] this->data;
  this->data = data;
}

void biguint_t::bit_and(const biguint_t &a, const biguint_t&b) {
  size = std::min(a.size, b.size);
  uint64_t *data = new uint64_t[size];
  for (size_t i = 0 ; i < size ; i++) {
    data[i] = a.data[i] & b.data[i];
  }
  if (this->data) delete[] this->data;
  this->data = data;
}

biguint_t biguint_t::operator=(const biguint_t &that) {
  if (this != &that) copy_biguint(that);
  return *this;
}

biguint_t biguint_t::operator<<(const size_t shamt) {
  biguint_t res;
  int offset = shamt / 64;
  int shift = shamt % 64;
  res.size = size + offset;
  res.data = new uint64_t[res.size];
  for (int i = size - 1 ; i >= 0 ; i--) {
    res.data[i+offset] = data[i] << shift;
    res.data[i+offset] |= i ? data[i-1] >> (64-shamt) : 0;
  }
  return res;
}

biguint_t biguint_t::operator>>(const size_t shamt) {
  biguint_t res;
  if (shamt < 64 * size) {
    int offset = shamt / 64;
    int shift = shamt % 64;
    uint64_t mask = (uint64_t(1) << shift) - 1;
    res.size = size - offset;
    res.data = new uint64_t[res.size];
    for (size_t i = 0 ; i < size - offset ; i++) {
      res.data[i] = data[i + offset] >> shift;
      res.data[i] |= data[i + offset + 1] & mask;
    }
  } else {
    res.size = 1;
    res.data = new uint64_t[res.size];
    res.data[0] = 0;
  }
  return res;
}

biguint_t biguint_t::operator|(const biguint_t &that) {
  biguint_t res;
  res.bit_or(*this, that);
  return res;
}

biguint_t biguint_t::operator&(const biguint_t &that) {
  biguint_t res;
  res.bit_and(*this, that);
  return res;
}

void biguint_t::operator|=(const biguint_t &that) {
  bit_or(*this, that);
}

void biguint_t::operator&=(const biguint_t &that) {
  bit_and(*this, that);
}

bool biguint_t::operator==(const biguint_t &that) {
  bool yes = size == that.size;
  if (yes) {
    for (size_t i = 0 ; i < size ; i++) {
      yes = data[i] == that.data[i];
      if (!yes) break;
    }
  }
  return yes;
}


std::ostream& operator<<(std::ostream &os, const biguint_t& value) {
  // prints hex!
  assert(value.size > 0);
  os << std::hex;
  os << value.data[value.size-1];
  os << std::setfill('0') << std::setw(16);
  for (int i = value.size - 2 ; i >= 0 ; i--) {
    os << value.data[i];
  }
  os << std::setfill(' ') << std::setw(0) << std::dec;
  return os;
}
