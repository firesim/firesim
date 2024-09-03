// See LICENSE for license details
#include <string.h>

#include "justl2.h"
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

char justl2_t::KIND;

// name length limit for ptys
#define SLAVENAMELEN 256

/* There is no "backpressure" to the user input for sigs. only one at a time
 * non-zero value represents unconsumed special char input.
 *
 * Reset to zero once consumed.
 */

// This is fine for multiple JUSTL2s because JUSTL2s > uart 0 will use pty, not
// stdio
char specialchar_justl2 = 0;

char justl2_ptyname[SLAVENAMELEN];

void sighand_justl2(int s) {
  switch (s) {
  case SIGINT:
    // ctrl-c
    specialchar_justl2 = 0x3;
    break;
  default:
    specialchar_justl2 = 0x0;
  }
}
#endif

/**
 * Helper class which links the JUSTL2 stream to primitive streams.
 */
class justl2_fd_handler : public justl2_handler {
public:
  justl2_fd_handler() = default;
  ~justl2_fd_handler() override;

  std::optional<char> get() override;
  void put(char data) override;

protected:
  int inputfd;
  int outputfd;
  int loggingfd = 0;
};

justl2_fd_handler::~justl2_fd_handler() { close(this->loggingfd); }

std::optional<char> justl2_fd_handler::get() {
  char inp;
  int readamt;
  if (specialchar_justl2) {
    // send special character (e.g. ctrl-c)
    // for stdin handling
    //
    // PTY should never trigger this
    inp = specialchar_justl2;
    specialchar_justl2 = 0;
    readamt = 1;
  } else {
    // else check if we have input
    readamt = ::read(inputfd, &inp, 1);
  }

  if (readamt <= 0)
    return std::nullopt;
  return inp;
}

void justl2_fd_handler::put(char data) {
  ::write(outputfd, &data, 1);
  if (loggingfd) {
    ::write(loggingfd, &data, 1);
  }
}

/**
 * JUSTL2 handler which fetches data from stdin and outputs to stdout.
 */
class justl2_stdin_handler final : public justl2_fd_handler {
public:
  justl2_stdin_handler() {
    // signal handler so ctrl-c doesn't kill simulation when JUSTL2 is attached
    // to stdin/stdout
    struct sigaction sigIntHandler;
    sigIntHandler.sa_handler = sighand_justl2;
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
 * JUSTL2 handler connected to PTY.
 */
class justl2_pty_handler final : public justl2_fd_handler {
public:
  justl2_pty_handler(int justl2no) {
    // for JUSTL2s that are not JUSTL20, use a PTY
    char slavename[SLAVENAMELEN];
    int ptyfd = posix_openpt(O_RDWR | O_NOCTTY);
    grantpt(ptyfd);
    unlockpt(ptyfd);
    ptsname_r(ptyfd, slavename, SLAVENAMELEN);

    strncpy(justl2_ptyname, slavename, 18);
    // create symlink for reliable location to find justl2 pty
    std::string symlinkname = std::string("justl2pty") + std::to_string(justl2no);
    // unlink in case symlink already exists
    unlink(symlinkname.c_str());
    symlink(slavename, symlinkname.c_str());
    printf("[JUSTL2 driver] JUSTL2%d is on PTY: %s, symlinked at %s\n",
           justl2no,
           slavename,
           symlinkname.c_str());
    printf("[JUSTL2 driver] Attach to this JUSTL2 with 'sudo screen %s' or 'sudo screen %s'\n",
           slavename,
           symlinkname.c_str());
    inputfd = ptyfd;
    outputfd = ptyfd;

    // also, for these we want to log output to file here.
    std::string justl2logname = std::string("justl2log") + std::to_string(justl2no);
    printf("[JUSTL2 driver] Logfile is being written to %s\n", justl2logname.c_str());
    this->loggingfd = open(justl2logname.c_str(), O_RDWR | O_CREAT, 0644);
    // Don't block on reads if there is nothing typed in
    fcntl(inputfd, F_SETFL, fcntl(inputfd, F_GETFL) | O_NONBLOCK);
  }
};

/**
 * JUSTL2 handler connected to files.
 */
class justl2_file_handler final : public justl2_fd_handler {
public:
  justl2_file_handler(const std::string &in_name, const std::string &out_name) {
    inputfd = open(in_name.c_str(), O_RDONLY);
    outputfd = open(out_name.c_str(), O_WRONLY | O_CREAT, 0644);
  }
};

static std::unique_ptr<justl2_handler>
create_handler(const std::vector<std::string> &args, int justl2no) {
  std::string in_arg = std::string("+justl2-in") + std::to_string(justl2no) + "=";
  std::string out_arg = std::string("+justl2-out") + std::to_string(justl2no) + "=";

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
    return std::make_unique<justl2_file_handler>(in_name, out_name);
  }

  return std::make_unique<justl2_pty_handler>(justl2no);
}

justl2_t::justl2_t(simif_t &simif,
               const JUSTL2BRIDGEMODULE_struct &mmio_addrs,
               int justl2no,
               const std::vector<std::string> &args)
    : bridge_driver_t(simif, &KIND), mmio_addrs(mmio_addrs),
      handler(create_handler(args, justl2no)) {}

justl2_t::~justl2_t() = default;

void justl2_t::send() {
  if (data.in.fire()) {
    write(mmio_addrs.in_bits, data.in.bits);
    write(mmio_addrs.in_valid, data.in.valid);
  }

  if (data.out.fire()){
    write(mmio_addrs.out_ready, data.out.ready);
  }
}

int justl2_t::read_l2_accesses(){
  return read(mmio_addrs.l2_accesses);
}

int justl2_t::read_l2_misses(){
  return read(mmio_addrs.l2_misses);
}

void justl2_t::recv() {
  data.in.ready = read(mmio_addrs.in_ready);
  data.out.valid = read(mmio_addrs.out_valid);
  if (data.out.valid){
    data.out.bits = read(mmio_addrs.out_bits);
    printf("\n[JUSTL2 driver] Receiving from HW: Hit = %X (%s)\n", data.out.bits, justl2_ptyname);
    
    if (data.out.bits == 0){ // miss
      // generate a three bit random number from 0 to 7
      data.in.bits = (rand() % 8);
      printf("\n[JUSTL2 driver] Sending victim way: %d (%s)\n", data.in.bits, justl2_ptyname);
      data.in.valid = true;
    }else if (data.out.bits > 1){ // print delay
      // generate a three bit random number from 0 to 7
      printf("\n[JUSTL2 driver] Printing stalling time: %d (%s)\n", data.out.bits, justl2_ptyname);
      data.in.valid = true;
    }  
    
  }
}

void justl2_t::tick() {
  data.out.ready = true;
  data.in.valid = false;
  
  do {
    this->recv();
    
    if (data.in.ready) {
      if (auto bits = handler->get()) {
        data.in.bits = *bits;
        data.in.valid = true;
        printf("[JUSTL2 driver] Receiving from keyboard: %c\n", *bits);
      }
    } 

    this->send();
    data.in.valid = false;
  } while (data.out.fire());
}
