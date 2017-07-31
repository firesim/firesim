#include <sstream>
#include <cstring>
#include <cassert>
#include <algorithm>
#include "biguint.h"

biguint_t::biguint_t(const char* v, size_t base) {
  // we only accepts hex or bin
  // TODO: convert decimals
  char prefix[3]; 
  const char* value;
  strncpy(prefix, v, 2);
  prefix[2] = '\0';
  if (strcmp(prefix, "0x") == 0 && (base == 0 || base == 16)) {
    value = &v[2];
    base = 16;
  } else if(strcmp(prefix, "0b") == 0 && (base == 0 || base == 2)) {
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

void biguint_t::init(const uint32_t value) {
  init(&value, 1);
}

void biguint_t::init(const uint32_t* value, const size_t s) {
  data = new uint32_t[size = s];
  memcpy(data, value, size*sizeof(uint32_t));
}

inline void pad_num(char *buf, const char* value, size_t padw) {
  for (size_t i = 0 ; i < padw ; i++) {
    buf[i] = '0';
  }
  strcpy(buf+padw, value);
}

void biguint_t::init_hex(const char* value) {
  int len = strlen(value);
  size = (len+HEX_WIDTH-1) / HEX_WIDTH;
  data = new uint32_t[size];
  // padding
  char *buf = new char[size*HEX_WIDTH+1];
  pad_num(buf, value, size*HEX_WIDTH-len);
  // converting
  for (size_t i = 0 ; i < size ; i++) {
    char num[HEX_WIDTH+1];
    strncpy(num, buf + i*HEX_WIDTH, HEX_WIDTH);
    num[HEX_WIDTH] = '\0';
    data[size-1-i] = hex_to_dec(num);
  }
  delete[] buf;
}

void biguint_t::init_bin(const char* value) {
  int len = strlen(value);
  size = (len+UINT_WIDTH-1) / UINT_WIDTH;
  data = new uint32_t[size];
  // padding
  char *buf = new char[size*UINT_WIDTH+1];
  pad_num(buf, value, size*UINT_WIDTH-len);
  // converting
  for (size_t i = 0 ; i < size ; i++) {
    char num[UINT_WIDTH+1];
    strncpy(num, buf + i*UINT_WIDTH, UINT_WIDTH);
    num[UINT_WIDTH] = '\0';
    data[size-1-i] = bin_to_dec(num);
  }
  delete[] buf;
}

void biguint_t::copy_biguint(const biguint_t &that) {
  if (data) delete[] data;
  if (that.data) {
    data = new uint32_t[size = that.size];
    for (size_t i = 0 ; i < size ; i++) {
      data[i] = that.data[i];
    }
  } else {
    data = NULL;
    size = 0;
  }
}

inline void trim_upper_nums(uint32_t **data, size_t* size) {
  size_t s = *size;
  while (!(*data)[s-1] && s > 1) s--;
  if (s < *size) {
    uint32_t *d = new uint32_t[s];
    for (size_t i = 0 ; i < s ; i++) {
      d[i] = (*data)[i];
    }
    delete[] *data;
    *size = s;
    *data = d;
  }
}

void biguint_t::bit_or(const biguint_t &a, const biguint_t &b) {
  size_t size = std::max(a.size, b.size);
  uint32_t *data = new uint32_t[size];
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
  this->size = size;
  this->data = data;
}

void biguint_t::bit_and(const biguint_t &a, const biguint_t&b) {
  size_t size = std::min(a.size, b.size);
  uint32_t *data = new uint32_t[size];
  for (size_t i = 0 ; i < size ; i++) {
    data[i] = a.data[i] & b.data[i];
  }
  trim_upper_nums(&data, &size);
  if (this->data) delete[] this->data;
  this->size = size;
  this->data = data;
}

biguint_t biguint_t::operator=(const biguint_t &that) {
  if (this != &that) copy_biguint(that);
  return *this;
}

biguint_t biguint_t::operator<<(const size_t shamt) {
  biguint_t res;
  size_t offset = shamt / UINT_WIDTH;
  size_t shift = shamt % UINT_WIDTH;
  res.size = size + offset;
  res.data = new uint32_t[res.size];
  for (int i = size - 1 ; i >= 0 ; i--) {
    res.data[i+offset] = data[i] << shift;
    res.data[i+offset] |= i ? data[i-1] >> (UINT_WIDTH-shamt) : 0;
  }
  for (size_t i = 0 ; i < offset ; i++) {
    res.data[i] = 0;
  }
  trim_upper_nums(&res.data, &res.size);
  return res;
}

biguint_t biguint_t::operator>>(const size_t shamt) {
  biguint_t res;
  if (shamt < size * UINT_WIDTH) {
    int offset = shamt / UINT_WIDTH;
    int shift = shamt % UINT_WIDTH;
    uint32_t mask = (1 << shift) - 1;
    res.size = size - offset;
    res.data = new uint32_t[res.size];
    for (size_t i = 0 ; i < res.size ; i++) {
      res.data[i] = data[i + offset] >> shift;
      if (i + offset + 1 < size) {
        res.data[i] |= (data[i + offset + 1] & mask) << (UINT_WIDTH-shift);
      }
    }
    trim_upper_nums(&res.data, &res.size);
  } else {
    res.size = 1;
    res.data = new uint32_t[res.size];
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
  bool yes = true;
  for (int i = 0 ; i < (int) (size - that.size) ; i++) {
    yes = data[that.size + i] == 0;
    if (!yes) break;
  }
  for (int i = 0 ; i < (int) (that.size - size) ; i++) {
    yes = that.data[size + i] == 0;
    if (!yes) break;
  }
  if (yes) {
    for (size_t i = 0 ; i < std::min(size, that.size) ; i++) {
      yes = data[i] == that.data[i];
      if (!yes) break;
    }
  }
  return yes;
}

std::ostream& operator<<(std::ostream &os, const biguint_t& value) {
  // prints hex!
  assert(value.size > 0);
  os << std::hex << value.data[value.size-1];
  for (int i = value.size - 2 ; i >= 0 ; i--) {
    os << std::hex << std::setfill('0') << std::setw(HEX_WIDTH) << value.data[i];
  }
  os << std::dec << std::setfill(' ') << std::setw(0);
  return os;
}

std::istream& operator>>(std::istream &is, biguint_t& value) {
  // assumes hex
  std::string hex;
  is >> hex;
  delete[] value.data;
  value.init_hex(hex.c_str());
  return is;
}
