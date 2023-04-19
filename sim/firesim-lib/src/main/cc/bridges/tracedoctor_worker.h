#ifndef __TRACEDOCTOR_WORKER_H_
#define __TRACEDOCTOR_WORKER_H_

#include <vector>
#include <tuple>
#include <string>
#include <map>
#include <cassert>

struct traceInfo {
  unsigned int tracerId;
  unsigned int tokenBits;
  unsigned int tokenBytes;
  unsigned int traceBits;
  unsigned int traceBytes;
};

void strReplaceAll(std::string &str, std::string const &from, std::string const &);

std::vector<std::string> strSplit(std::string const &, std::string const &);

enum fileRegisterFields {freg_name = 0, freg_descriptor = 1, freg_file = 2};

#define TDWORKER_NO_FILES   0
#define TDWORKER_ANY_FILES -1

class tracedoctor_worker {
protected:
  std::string const name;
  std::string const tracerName;
  struct traceInfo const info;
  std::vector<std::tuple<std::string, FILE *, bool>> fileRegister;

  FILE * openFile(std::string const &filename, std::string const &compressionCmd = "", unsigned int const compressionLevel = 1, unsigned int const compressionThreads = 1);
  void closeFile(FILE * const);
  void closeFiles(void);

public:
  tracedoctor_worker(std::string const &name, std::vector<std::string> const &args, struct traceInfo const &info, int const requiredFiles = TDWORKER_NO_FILES);
  virtual void tick(char const * const data, unsigned int tokens);
  virtual ~tracedoctor_worker();
};

class tracedoctor_dummy : public tracedoctor_worker {
public:
  tracedoctor_dummy(std::vector<std::string> const &args, struct traceInfo const &info);
};

class tracedoctor_filer : public tracedoctor_worker {
private:
  uint64_t byteCount;
  bool raw;
public:
  tracedoctor_filer(std::vector<std::string> const &args, struct traceInfo const &info);
  ~tracedoctor_filer() override;
  void tick(char const * const data, unsigned int tokens) override;
};

#endif // __TRACEDOCTOR_WORKER_H_
