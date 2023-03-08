#include "tracedoctor_worker.h"

#include <cstring>
#include <stdexcept>

void strReplaceAll(std::string &str, std::string const from, std::string const to) {
    if(from.empty())
        return;
    size_t start_pos = 0;
    while((start_pos = str.find(from, start_pos)) != std::string::npos) {
        str.replace(start_pos, from.length(), to);
        start_pos += to.length(); // In case 'to' contains 'from', like replacing 'x' with 'yx'
    }
}

std::vector<std::string> strSplit(std::string const str, std::string const sep) {
    char* cstr = const_cast<char*>(str.c_str());
    char* current;
    std::vector<std::string> arr;
    current = strtok(cstr,sep.c_str());
    while (current!=NULL) {
        arr.push_back(current);
        current = strtok(NULL, sep.c_str());
    }
    return arr;
}

tracedoctor_worker::tracedoctor_worker(std::string const name, std::vector<std::string> const args, struct traceInfo const info, int const requiredFiles) : name(name), tracerName(name + "@" + std::to_string(info.tracerId)), info(info) {
  if (requiredFiles != TDWORKER_NO_FILES) {
    unsigned int localRequiredFiles = requiredFiles;
    std::vector<std::string> filesToOpen;

    for (auto &a: args) {
      std::vector<std::string> c = strSplit(a, ":");
      if (c[0].compare("file") == 0 && c.size() > 1 && localRequiredFiles > 0) {
        filesToOpen.push_back(c[1]);
        localRequiredFiles--;
      } else if (c[0].compare("compressionThreads") == 0 && c.size() > 1) {
        compressionThreads = std::stoul(c[1], nullptr, 0);
      } else if (c[0].compare("compressionLevel") == 0 && c.size() > 1) {
        compressionLevel = std::stoul(c[1], nullptr, 0);
      }
    }

    if (localRequiredFiles != 0) {
      throw std::invalid_argument("TraceDoctor Worker " + tracerName + " requires " + std::to_string(requiredFiles) + "file, provide it via e.g. 'file:output.csv'");
    }

    for (auto &a : filesToOpen) {
      openFile(a);
    }
  }
}

void tracedoctor_worker::tick(char const * const data, unsigned int tokens) {
  (void) data; (void) tokens;
}

FILE * tracedoctor_worker::openFile(std::string const fileName) {
  std::string localFileName = fileName;
  strReplaceAll(localFileName, std::string("%id"), std::to_string(info.tracerId));

  bool compressed = false;
  std::pair<std::string , bool> compressApp;
  FILE *fileDescriptor;

  std::map<std::string, std::pair<std::string, bool>> const compressionMap = {
    {".gz",  {"gzip", false}},
    {".bz2", {"bzip2", false}},
    {".xz",  {"xz -T", true}},
    {".zst", {"zstd -T", true}},
  };

  for (const auto &c: compressionMap) {
    if (c.first.size() <= localFileName.size() && std::equal(c.first.rbegin(), c.first.rend(), localFileName.rbegin())) {
      compressed = true;
      compressApp = c.second;
      break;
    }
  }

  if (compressed) {
    std::string cmd = compressApp.first;
    if (compressApp.second) {
      cmd += std::to_string(compressionThreads);
    }
    cmd += std::string(" -") + std::to_string(compressionLevel) + std::string(" - >") + localFileName;
    fileDescriptor = popen(cmd.c_str(), "w");
    if (fileDescriptor == NULL)
      throw std::invalid_argument("Could not execute " + cmd);
    fileRegister.emplace_back(localFileName, fileDescriptor, false);
  } else {
    fileDescriptor = fopen(localFileName.c_str(), "w");
    if (fileDescriptor == NULL)
      throw std::invalid_argument("Could not open " + localFileName);
    fileRegister.emplace_back(localFileName, fileDescriptor, true);
  }

  return fileDescriptor;
}

void tracedoctor_worker::closeFile(FILE * const fileDescriptor) {
  auto it = fileRegister.begin();
  while (it != fileRegister.end()) {
    if (std::get<freg_descriptor>(*it) == fileDescriptor) {
      if (std::get<freg_file>(*it)) {
        fclose(std::get<freg_descriptor>(*it));
      } else {
        pclose(std::get<freg_descriptor>(*it));
      }
      it = fileRegister.erase(it);
      break;
    }
  }
}

void tracedoctor_worker::closeFiles(void) {
  auto it = fileRegister.begin();
  while (it != fileRegister.end()) {
    if (std::get<freg_file>(*it)) {
      fclose(std::get<freg_descriptor>(*it));
    } else {
      pclose(std::get<freg_descriptor>(*it));
    }
    it = fileRegister.erase(it);
  }
}

tracedoctor_worker::~tracedoctor_worker() {
  closeFiles();
}

tracedoctor_filedumper::tracedoctor_filedumper(std::vector<std::string> const args, struct traceInfo const info)
    : tracedoctor_worker("Filer", args, info, 1), byteCount(0), raw(false)
{
  for (auto &a: args) {
    if (a == "raw") {
      raw = true;
      continue;
    }
  }

  if (info.traceBytes == info.tokenBytes) {
    raw = true;
  }

  // TODO: currently the file dumper does not support non-raw tracing if data is transferred over multiple tokens
  // this would require a slightly different algorithm that keeps track of partial data over tick boundaries
  assert(raw || info.traceBytes <= info.tokenBytes);

  fprintf(stdout, "%s: file(%s), raw(%d)\n", tracerName.c_str(), std::get<freg_name>(fileRegister[0]).c_str(), raw);
}

tracedoctor_filedumper::~tracedoctor_filedumper() {
    fprintf(stdout, "%s: file(%s), bytes_stored(%ld)\n", tracerName.c_str(), std::get<freg_name>(fileRegister[0]).c_str(), byteCount);
}

void tracedoctor_filedumper::tick(char const * const data, unsigned int tokens) {
  if (raw) {
    fwrite(data, 1, tokens * info.tokenBytes, std::get<freg_descriptor>(fileRegister[0]));
    byteCount += tokens * info.tokenBytes;
  } else {
    for (unsigned int i = 0; i < tokens; i++) {
      fwrite(&data[i * info.tokenBytes], 1, info.traceBytes, std::get<freg_descriptor>(fileRegister[0]));
    }
    byteCount += tokens * info.traceBytes;
  }
}
