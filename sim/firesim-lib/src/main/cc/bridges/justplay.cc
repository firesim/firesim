// See LICENSE for license details
#include <string.h>

#include "justplay.h"
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

char justplay_t::KIND;

// name length limit for ptys
#define SLAVENAMELEN 256

/* There is no "backpressure" to the user input for sigs. only one at a time
 * non-zero value represents unconsumed special char input.
 *
 * Reset to zero once consumed.
 */

// This is fine for multiple JUSTPLAYs because JUSTPLAYs > uart 0 will use pty, not
// stdio
char specialchar_justplay = 0;

char justplay_ptyname[SLAVENAMELEN];

void sighand_justplay(int s) {
  switch (s) {
  case SIGINT:
    // ctrl-c
    specialchar_justplay = 0x3;
    break;
  default:
    specialchar_justplay = 0x0;
  }
}
#endif

/**
 * Helper class which links the JUSTPLAY stream to primitive streams.
 */
class justplay_fd_handler : public justplay_handler {
public:
  justplay_fd_handler() = default;
  ~justplay_fd_handler() override;

  std::optional<char> get() override;
  void put(char data) override;

protected:
  int inputfd;
  int outputfd;
  int loggingfd = 0;
};

justplay_fd_handler::~justplay_fd_handler() { close(this->loggingfd); }

std::optional<char> justplay_fd_handler::get() {
  char inp;
  int readamt;
  if (specialchar_justplay) {
    // send special character (e.g. ctrl-c)
    // for stdin handling
    //
    // PTY should never trigger this
    inp = specialchar_justplay;
    specialchar_justplay = 0;
    readamt = 1;
  } else {
    // else check if we have input
    readamt = ::read(inputfd, &inp, 1);
  }

  if (readamt <= 0)
    return std::nullopt;
  return inp;
}

void justplay_fd_handler::put(char data) {
  ::write(outputfd, &data, 1);
  if (loggingfd) {
    ::write(loggingfd, &data, 1);
  }
}

/**
 * JUSTPLAY handler which fetches data from stdin and outputs to stdout.
 */
class justplay_stdin_handler final : public justplay_fd_handler {
public:
  justplay_stdin_handler() {
    // signal handler so ctrl-c doesn't kill simulation when JUSTPLAY is attached
    // to stdin/stdout
    struct sigaction sigIntHandler;
    sigIntHandler.sa_handler = sighand_justplay;
    sigemptyset(&sigIntHandler.sa_mask);
    sigIntHandler.sa_flags = 0;
    sigaction(SIGINT, &sigIntHandler, nullptr);
    
    inputfd = STDIN_FILENO;
    outputfd = STDOUT_FILENO;
    // Don't block on reads if there is nothing typed in
    fcntl(inputfd, F_SETFL, fcntl(inputfd, F_GETFL) | O_NONBLOCK);
  }
};

/**
 * JUSTPLAY handler connected to PTY.
 */
class justplay_pty_handler final : public justplay_fd_handler {
public:
  justplay_pty_handler(int justplayno) {
    // for JUSTPLAYs that are not JUSTPLAY0, use a PTY
    char slavename[SLAVENAMELEN];
    int ptyfd = posix_openpt(O_RDWR | O_NOCTTY);
    grantpt(ptyfd);
    unlockpt(ptyfd);
    ptsname_r(ptyfd, slavename, SLAVENAMELEN);

    strncpy(justplay_ptyname, slavename, 18);
    // create symlink for reliable location to find justplay pty
    std::string symlinkname = std::string("justplaypty") + std::to_string(justplayno);
    // unlink in case symlink already exists
    unlink(symlinkname.c_str());
    symlink(slavename, symlinkname.c_str());
    printf("[JUSTPLAY driver] JUSTPLAY%d is on PTY: %s, symlinked at %s\n",
           justplayno,
           slavename,
           symlinkname.c_str());
    printf("[JUSTPLAY driver] Attach to this JUSTPLAY with 'sudo screen %s' or 'sudo screen %s'\n",
           slavename,
           symlinkname.c_str());
    inputfd = ptyfd;
    outputfd = ptyfd;

    // also, for these we want to log output to file here.
    std::string justplaylogname = std::string("justplaylog") + std::to_string(justplayno);
    printf("[JUSTPLAY driver] Logfile is being written to %s\n", justplaylogname.c_str());
    this->loggingfd = open(justplaylogname.c_str(), O_RDWR | O_CREAT, 0644);
    // Don't block on reads if there is nothing typed in
    fcntl(inputfd, F_SETFL, fcntl(inputfd, F_GETFL) | O_NONBLOCK);
  }
};

/**
 * JUSTPLAY handler connected to files.
 */
class justplay_file_handler final : public justplay_fd_handler {
public:
  justplay_file_handler(const std::string &in_name, const std::string &out_name) {
    inputfd = open(in_name.c_str(), O_RDONLY);
    outputfd = open(out_name.c_str(), O_WRONLY | O_CREAT, 0644);
  }
};

static std::unique_ptr<justplay_handler>
create_handler(const std::vector<std::string> &args, int justplayno) {
  std::string in_arg = std::string("+justplay-in") + std::to_string(justplayno) + "=";
  std::string out_arg = std::string("+justplay-out") + std::to_string(justplayno) + "=";

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
    return std::make_unique<justplay_file_handler>(in_name, out_name);
  }

  return std::make_unique<justplay_pty_handler>(justplayno);
}

justplay_t::justplay_t(simif_t &simif,
               const JUSTPLAYBRIDGEMODULE_struct &mmio_addrs,
               int justplayno,
               const std::vector<std::string> &args)
    : bridge_driver_t(simif, &KIND), mmio_addrs(mmio_addrs),
      handler(create_handler(args, justplayno)) {}

justplay_t::~justplay_t() = default;

void justplay_t::send() {
  if (data.in.fire()) {
    write(mmio_addrs.in_bits, data.in.bits);
    write(mmio_addrs.in_valid, data.in.valid);
  }

  if (data.out.fire()){
    write(mmio_addrs.out_ready, data.out.ready);
  }
}

void justplay_t::recv() {
  data.in.ready = read(mmio_addrs.in_ready);
  data.out.valid = read(mmio_addrs.out_valid);
  if (data.out.valid){
    printf("\n[JUSTPLAY driver] Receiving from HW: %c %d %X %s\n", read(mmio_addrs.out_bits), read(mmio_addrs.out_bits), read(mmio_addrs.out_bits), justplay_ptyname);
    data.out.bits = read(mmio_addrs.out_bits);
    if (data.out.bits%2 == 0){
      data.in.bits = data.out.bits >> 1;
    }else{
      data.in.bits = data.out.bits;
    }  
    data.in.valid = true;
  }
}

void justplay_t::tick() {
  data.out.ready = true;
  data.in.valid = false;
  
  do {
    this->recv();
    
    if (data.in.ready) {
      if (auto bits = handler->get()) {
        data.in.bits = *bits;
        data.in.valid = true;
        printf("[JUSTPLAY driver] Receiving from keyboard: %c\n", *bits);
      }
    } 

    this->send();
    data.in.valid = false;
  } while (data.out.fire());
}
