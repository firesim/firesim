// See LICENSE for license details

#include "zzz.h"
#include "core/simif.h"

#include <fcntl.h>
#include <sys/stat.h>

#ifndef _XOPEN_SOURCE
#define _XOPEN_SOURCE
#endif

#include <stdio.h>
#include <stdlib.h>

#ifndef _WIN32
#include <unistd.h>

char zzz_t::KIND;

// name length limit for ptys
#define SLAVENAMELEN 256

/* There is no "backpressure" to the user input for sigs. only one at a time
 * non-zero value represents unconsumed special char input.
 *
 * Reset to zero once consumed.
 */

// This is fine for multiple ZZZs because ZZZs > uart 0 will use pty, not
// stdio
char specialchar_zzz = 0;

void sighand_zzz(int s) {
  switch (s) {
  case SIGINT:
    // ctrl-c
    specialchar_zzz = 0x3;
    break;
  default:
    specialchar_zzz = 0x0;
  }
}
#endif

/**
 * Helper class which links the ZZZ stream to primitive streams.
 */
class zzz_fd_handler : public zzz_handler {
public:
  zzz_fd_handler() = default;
  ~zzz_fd_handler() override;

  std::optional<char> get() override;
  void put(char data) override;

protected:
  int inputfd;
  int outputfd;
  int loggingfd = 0;
};

zzz_fd_handler::~zzz_fd_handler() { close(this->loggingfd); }

std::optional<char> zzz_fd_handler::get() {
  char inp;
  int readamt;
  if (specialchar_zzz) {
    // send special character (e.g. ctrl-c)
    // for stdin handling
    //
    // PTY should never trigger this
    inp = specialchar_zzz;
    specialchar_zzz = 0;
    readamt = 1;
  } else {
    // else check if we have input
    readamt = ::read(inputfd, &inp, 1);
  }

  if (readamt <= 0)
    return std::nullopt;
  return inp;
}

void zzz_fd_handler::put(char data) {
  ::write(outputfd, &data, 1);
  if (loggingfd) {
    ::write(loggingfd, &data, 1);
  }
}

/**
 * ZZZ handler which fetches data from stdin and outputs to stdout.
 */
class zzz_stdin_handler final : public zzz_fd_handler {
public:
  zzz_stdin_handler() {
    // signal handler so ctrl-c doesn't kill simulation when ZZZ is attached
    // to stdin/stdout
    struct sigaction sigIntHandler;
    sigIntHandler.sa_handler = sighand_zzz;
    sigemptyset(&sigIntHandler.sa_mask);
    sigIntHandler.sa_flags = 0;
    sigaction(SIGINT, &sigIntHandler, nullptr);
    printf("ZZZ0 is here (stdin/stdout).\n");
    inputfd = STDIN_FILENO;
    outputfd = STDOUT_FILENO;
    // Don't block on reads if there is nothing typed in
    fcntl(inputfd, F_SETFL, fcntl(inputfd, F_GETFL) | O_NONBLOCK);
  }
};

/**
 * ZZZ handler connected to PTY.
 */
class zzz_pty_handler final : public zzz_fd_handler {
public:
  zzz_pty_handler(int zzzno) {
    // for ZZZs that are not ZZZ0, use a PTY
    char slavename[SLAVENAMELEN];
    int ptyfd = posix_openpt(O_RDWR | O_NOCTTY);
    grantpt(ptyfd);
    unlockpt(ptyfd);
    ptsname_r(ptyfd, slavename, SLAVENAMELEN);

    // create symlink for reliable location to find zzz pty
    std::string symlinkname = std::string("zzzpty") + std::to_string(zzzno);
    // unlink in case symlink already exists
    unlink(symlinkname.c_str());
    symlink(slavename, symlinkname.c_str());
    printf("ZZZ%d is on PTY: %s, symlinked at %s\n",
           zzzno,
           slavename,
           symlinkname.c_str());
    printf("Attach to this ZZZ with 'sudo screen %s' or 'sudo screen %s'\n",
           slavename,
           symlinkname.c_str());
    inputfd = ptyfd;
    outputfd = ptyfd;

    // also, for these we want to log output to file here.
    std::string zzzlogname = std::string("zzzlog_DN_") + std::to_string(zzzno);
    printf("ZZZ logfile is being written to %s\n", zzzlogname.c_str());
    this->loggingfd = open(zzzlogname.c_str(), O_RDWR | O_CREAT, 0644);
    // Don't block on reads if there is nothing typed in
    fcntl(inputfd, F_SETFL, fcntl(inputfd, F_GETFL) | O_NONBLOCK);
  }
};

/**
 * ZZZ handler connected to files.
 */
class zzz_file_handler final : public zzz_fd_handler {
public:
  zzz_file_handler(const std::string &in_name, const std::string &out_name) {
    inputfd = open(in_name.c_str(), O_RDONLY);
    outputfd = open(out_name.c_str(), O_WRONLY | O_CREAT, 0644);
  }
};

static std::unique_ptr<zzz_handler>
create_handler(const std::vector<std::string> &args, int zzzno) {
  std::string in_arg = std::string("+zzz-in") + std::to_string(zzzno) + "=";
  std::string out_arg = std::string("+zzz-out") + std::to_string(zzzno) + "=";

  std::string in_name, out_name;
  for (const auto &arg : args) {
    if (arg.find(in_arg) == 0) {
      in_name = const_cast<char *>(arg.c_str()) + in_arg.length();
    }
    if (arg.find(out_arg) == 0) {
      out_name = const_cast<char *>(arg.c_str()) + out_arg.length();
    }
  }

  if (!in_name.empty() && !out_name.empty()) {
    return std::make_unique<zzz_file_handler>(in_name, out_name);
  }
  // if (zzzno == 0) {
  //   return std::make_unique<zzz_stdin_handler>();
  // }
  return std::make_unique<zzz_pty_handler>(zzzno);
  //return std::make_unique<zzz_stdin_handler>();
}

zzz_t::zzz_t(simif_t &simif,
               const ZZZBRIDGEMODULE_struct &mmio_addrs,
               int zzzno,
               const std::vector<std::string> &args)
    : bridge_driver_t(simif, &KIND), mmio_addrs(mmio_addrs),
      handler(create_handler(args, zzzno)) {}

zzz_t::~zzz_t() = default;

void zzz_t::send() {
  // if (data.in.fire()) {
  //   write(mmio_addrs.in_bits, data.in.bits);
  //   write(mmio_addrs.in_valid, data.in.valid);
  // }
  if (data.inDN.fire()) {
    write(mmio_addrs.inDN_bits, data.inDN.bits);
    write(mmio_addrs.inDN_valid, data.inDN.valid);
    printf("\nZZZ sending to UART: %c\n", data.inDN.bits);
  }
  // if (data.out.fire()) {
  //   write(mmio_addrs.out_ready, data.out.ready);
    
  // }
  if (data.outDN.fire()){
    write(mmio_addrs.outDN_ready, data.outDN.ready);
  }
}

void zzz_t::recv() {
  // data.in.ready = read(mmio_addrs.in_ready);
  // data.out.valid = read(mmio_addrs.out_valid);
  // if (data.out.valid) {
  //   data.out.bits = read(mmio_addrs.out_bits);
  // }
  data.inDN.ready = read(mmio_addrs.inDN_ready);
  data.outDN.valid = read(mmio_addrs.outDN_valid);
  if (data.outDN.valid)
    printf("\nDN receiving: %c\n", read(mmio_addrs.outDN_bits));
}

void zzz_t::tick() {
  // data.out.ready = true;
  data.outDN.ready = true;
  
  // data.in.valid = false;
  data.inDN.valid = false;
  
  do {
    this->recv();
    /*
    if (data.in.ready) {
      if (auto bits = handler->get()) {
        data.in.bits = *bits;
        data.in.valid = true;
      }
    } */
    if (data.inDN.ready) {
      if (auto bits = handler->get()) {
        data.inDN.bits = *bits;
        data.inDN.valid = true;
        printf("ZZZ: receiving from keyboard: %c\n", *bits);
      }
    }
    // print in the screen
    // if (data.out.fire()) {
    //   handler->put(data.out.bits);
    //   //printf("\nDN receiving ZZZ: %c\n", (char)data.out.bits);
    // }

    this->send();
    // data.in.valid = false;
    data.inDN.valid = false;
  } while (data.inDN.fire() || data.outDN.fire());
}
