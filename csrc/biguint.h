#ifndef __BIGUINT_H
#define __BIGUINT_H

#include <stdint.h>
#include <ostream>

class biguint_t {
public:
  // constructor
  biguint_t() { data = NULL; size = 0; }
  biguint_t(const uint8_t value) { init(value); }
  biguint_t(const uint16_t value) { init(value); }
  biguint_t(const uint32_t value) { init(value); }
  biguint_t(const uint64_t value) { init(value); }
  biguint_t(const int8_t value) { init(value); }
  biguint_t(const int16_t value) { init(value); }
  biguint_t(const int32_t value) { init(value); }
  biguint_t(const int64_t value) { init(value); }
  biguint_t(const uint64_t *v, size_t s) { init(v, s); }
  biguint_t(const char* value, size_t base = 16);
  biguint_t(const biguint_t& that);
  ~biguint_t() { if (size > 0) delete[] data; }
  uint64_t uint() { return data[0]; }
  std::string str();
  biguint_t operator<<(const size_t shamt);
  biguint_t operator>>(const size_t shamt);
  biguint_t operator=(const biguint_t &that);
  biguint_t operator|(const biguint_t &that);
  biguint_t operator&(const biguint_t &that);
  void operator&=(const biguint_t &that);
  void operator|=(const biguint_t &that);
  bool operator==(const biguint_t &that);
  friend std::ostream& operator<<(std::ostream &os, const biguint_t& value);
private:
  void init(const uint64_t value);
  void init(const uint64_t* value, size_t size);
  void init_hex(const char* hex);
  void init_bin(const char* bin);
  void copy_biguint(const biguint_t &that);
  void bit_or(const biguint_t &a, const biguint_t &b);
  void bit_and(const biguint_t &a, const biguint_t &b);
  size_t size;
  uint64_t *data;   
};

#define parse_nibble(c) ((c) >= 'a' ? (c)-'a'+10 : (c)-'0')

inline uint64_t hex_to_dec(const char *hex) {
  uint64_t value = 0;
  while(*hex) {
    value = (value << 4) | parse_nibble(*hex);
    hex++;
  }
  return value;
}

inline uint64_t bin_to_dec(const char *bin) {
  uint64_t value = 0;
  while(*bin) {
    value = (value << 1) | (*bin - '0');
    bin++;
  }
  return value;
}

#endif // __BIGUINT_H
