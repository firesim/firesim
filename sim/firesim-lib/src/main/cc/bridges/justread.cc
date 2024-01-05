// See LICENSE for license details

#include "justread.h"
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

char justread_t::KIND;

// name length limit for ptys
#define SLAVENAMELEN 256

/* There is no "backpressure" to the user input for sigs. only one at a time
 * non-zero value represents unconsumed special char input.
 *
 * Reset to zero once consumed.
 */

// This is fine for multiple JUSTREADs because JUSTREADs > uart 0 will use pty, not
// stdio
char specialchar_justread = 0;

void sighand_justread(int s) {
  switch (s) {
  case SIGINT:
    // ctrl-c
    specialchar_justread = 0x3;
    break;
  default:
    specialchar_justread = 0x0;
  }
}
#endif

/**
 * Helper class which links the JUSTREAD stream to primitive streams.
 */
class justread_fd_handler : public justread_handler {
public:
  justread_fd_handler() = default;
  ~justread_fd_handler() override;

  std::optional<char> get() override;
  void put(char data) override;

protected:
  int inputfd;
  int outputfd;
  int loggingfd = 0;
};

justread_fd_handler::~justread_fd_handler() { close(this->loggingfd); }

std::optional<char> justread_fd_handler::get() {
  char inp;
  int readamt;
  if (specialchar_justread) {
    // send special character (e.g. ctrl-c)
    // for stdin handling
    //
    // PTY should never trigger this
    inp = specialchar_justread;
    specialchar_justread = 0;
    readamt = 1;
  } else {
    // else check if we have input
    readamt = ::read(inputfd, &inp, 1);
  }

  if (readamt <= 0)
    return std::nullopt;
  return inp;
}

void justread_fd_handler::put(char data) {
  ::write(outputfd, &data, 1);
  if (loggingfd) {
    ::write(loggingfd, &data, 1);
  }
}

/**
 * JUSTREAD handler which fetches data from stdin and outputs to stdout.
 */
class justread_stdin_handler final : public justread_fd_handler {
public:
  justread_stdin_handler() {
    // signal handler so ctrl-c doesn't kill simulation when JUSTREAD is attached
    // to stdin/stdout
    struct sigaction sigIntHandler;
    sigIntHandler.sa_handler = sighand_justread;
    sigemptyset(&sigIntHandler.sa_mask);
    sigIntHandler.sa_flags = 0;
    sigaction(SIGINT, &sigIntHandler, nullptr);
    printf("JUSTREAD0 is here (stdin/stdout).\n");
    inputfd = STDIN_FILENO;
    outputfd = STDOUT_FILENO;
    // Don't block on reads if there is nothing typed in
    fcntl(inputfd, F_SETFL, fcntl(inputfd, F_GETFL) | O_NONBLOCK);
  }
};

/**
 * JUSTREAD handler connected to PTY.
 */
class justread_pty_handler final : public justread_fd_handler {
public:
  justread_pty_handler(int justreadno) {
    // for JUSTREADs that are not JUSTREAD0, use a PTY
    char slavename[SLAVENAMELEN];
    int ptyfd = posix_openpt(O_RDWR | O_NOCTTY);
    grantpt(ptyfd);
    unlockpt(ptyfd);
    ptsname_r(ptyfd, slavename, SLAVENAMELEN);

    // create symlink for reliable location to find justread pty
    std::string symlinkname = std::string("justreadpty") + std::to_string(justreadno);
    // unlink in case symlink already exists
    unlink(symlinkname.c_str());
    symlink(slavename, symlinkname.c_str());
    printf("JUSTREAD%d is on PTY: %s, symlinked at %s\n",
           justreadno,
           slavename,
           symlinkname.c_str());
    printf("Attach to this JUSTREAD with 'sudo screen %s' or 'sudo screen %s'\n",
           slavename,
           symlinkname.c_str());
    inputfd = ptyfd;
    outputfd = ptyfd;

    // also, for these we want to log output to file here.
    std::string justreadlogname = std::string("justreadlog") + std::to_string(justreadno);
    printf("JUSTREAD logfile is being written to %s\n", justreadlogname.c_str());
    this->loggingfd = open(justreadlogname.c_str(), O_RDWR | O_CREAT, 0644);
    // Don't block on reads if there is nothing typed in
    fcntl(inputfd, F_SETFL, fcntl(inputfd, F_GETFL) | O_NONBLOCK);
  }
};

/**
 * JUSTREAD handler connected to files.
 */
class justread_file_handler final : public justread_fd_handler {
public:
  justread_file_handler(const std::string &in_name, const std::string &out_name) {
    inputfd = open(in_name.c_str(), O_RDONLY);
    outputfd = open(out_name.c_str(), O_WRONLY | O_CREAT, 0644);
  }
};

static std::unique_ptr<justread_handler>
create_handler(const std::vector<std::string> &args, int justreadno) {
  std::string in_arg = std::string("+justread-in") + std::to_string(justreadno) + "=";
  std::string out_arg = std::string("+justread-out") + std::to_string(justreadno) + "=";

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
    return std::make_unique<justread_file_handler>(in_name, out_name);
  }
  // if (justreadno == 0) {
  //   return std::make_unique<justread_stdin_handler>();
  // }
  return std::make_unique<justread_pty_handler>(justreadno);
  //return std::make_unique<justread_stdin_handler>();
}

justread_t::justread_t(simif_t &simif,
               const JUSTREADBRIDGEMODULE_struct &mmio_addrs,
               int justreadno,
               const std::vector<std::string> &args)
    : bridge_driver_t(simif, &KIND), mmio_addrs(mmio_addrs),
      handler(create_handler(args, justreadno)) {}

justread_t::~justread_t() = default;

void justread_t::send() {
  // if (data.in.fire()) {
  //   write(mmio_addrs.in_bits, data.in.bits);
  //   write(mmio_addrs.in_valid, data.in.valid);
  // }
//   if (data.inDN.fire()) {
//     write(mmio_addrs.inDN_bits, data.inDN.bits);
//     write(mmio_addrs.inDN_valid, data.inDN.valid);
//     printf("\nJUSTREAD sending to UART: %c\n", data.inDN.bits);
//   }
  // if (data.out.fire()) {
  //   write(mmio_addrs.out_ready, data.out.ready);
    
  // }
  if (data.out.fire()){
    write(mmio_addrs.out_ready, data.out.ready);
  }
}

void justread_t::recv() {
  // data.in.ready = read(mmio_addrs.in_ready);
  // data.out.valid = read(mmio_addrs.out_valid);
  // if (data.out.valid) {
  //   data.out.bits = read(mmio_addrs.out_bits);
  // }
//   data.inDN.ready = read(mmio_addrs.inDN_ready);
  data.out.valid = read(mmio_addrs.out_valid);
  if (data.out.valid)
    printf("\nJUSTREAD receiving: %c %d %X\n", read(mmio_addrs.out_bits), read(mmio_addrs.out_bits), read(mmio_addrs.out_bits));
}

void justread_t::tick() {
  // data.out.ready = true;
  data.out.ready = true;
  
  // data.in.valid = false;
//   data.inDN.valid = false;
  
  do {
    this->recv();
    /*
    if (data.in.ready) {
      if (auto bits = handler->get()) {
        data.in.bits = *bits;
        data.in.valid = true;
      }
    } */
    // if (data.inDN.ready) {
    //   if (auto bits = handler->get()) {
    //     data.inDN.bits = *bits;
    //     data.inDN.valid = true;
    //     printf("JUSTREAD: receiving from keyboard: %c\n", *bits);
    //   }
    // }
    // print in the screen
    // if (data.out.fire()) {
    //   handler->put(data.out.bits);
    //   //printf("\nDN receiving JUSTREAD: %c\n", (char)data.out.bits);
    // }

    this->send();
    // data.in.valid = false;
    // data.inDN.valid = false;
  } while (data.out.fire());
}
