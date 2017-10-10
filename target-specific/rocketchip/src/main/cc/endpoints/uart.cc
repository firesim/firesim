// See LICENSE for license details.

#include "uart.h"
#ifndef _WIN32
#include <unistd.h>
#include <fcntl.h>

/* There is no "backpressure" to the user input for sigs. only one at a time
 * non-zero value represents unconsumed special char input.
 *
 * Reset to zero once consumed.
 */
char specialchar = 0;

void sighand(int s) {
    switch (s) {
        case SIGINT:
            // ctrl-c
            specialchar = 0x3;
            break;
        default:
            specialchar = 0x0;
    }
}
#endif

uart_t::uart_t(simif_t* sim): endpoint_t(sim)
{
#ifndef _WIN32
    // Don't block on stdin reads if there is nothing typed in
    fcntl(STDIN_FILENO, F_SETFL, fcntl(STDIN_FILENO, F_GETFL) | O_NONBLOCK);

    // signal handler so ctrl-c doesn't kill simulation
    struct sigaction sigIntHandler;
    sigIntHandler.sa_handler = sighand;
    sigemptyset(&sigIntHandler.sa_mask);
    sigIntHandler.sa_flags = 0;
    sigaction(SIGINT, &sigIntHandler, NULL);
#endif
}

void uart_t::send() {
  if (data.in.fire()) {
    write(UARTWIDGET_0(in_bits), data.in.bits);
    write(UARTWIDGET_0(in_valid), data.in.valid);
  }
  if (data.out.fire()) {
    write(UARTWIDGET_0(out_ready), data.out.ready);
  }
}

void uart_t::recv() {
  data.in.ready = read(UARTWIDGET_0(in_ready));
  data.out.valid = read(UARTWIDGET_0(out_valid));
  if (data.out.valid) {
    data.out.bits = read(UARTWIDGET_0(out_bits));
  }
}

void uart_t::tick() {
  data.out.ready = true;
  data.in.valid = false;
  do {
    this->recv();

#ifndef _WIN32
    if (data.in.ready) {
        char inp;
        int readamt;
        if (specialchar) {
            // send special character (e.g. ctrl-c)
            inp = specialchar;
            specialchar = 0;
            readamt = 1;
        } else {
            // else check if we have input on stdin
            readamt = ::read(STDIN_FILENO, &inp, 1);
        }

        if (readamt > 0) {
            data.in.bits = inp;
            data.in.valid = true;
        }
    }
#endif

    if (data.out.fire()) {
      fprintf(stdout, "%c", data.out.bits);
      // always flush to get char-by-char output (not line-buffered)
      fflush(stdout);
    }

    this->send();
    data.in.valid = false;
  } while(data.in.fire() || data.out.fire());
}
