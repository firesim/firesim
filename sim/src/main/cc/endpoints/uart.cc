#ifdef UARTWIDGET_struct_guard

#include "uart.h"
#include <sys/stat.h>
#include <fcntl.h>

#define _XOPEN_SOURCE
#include <stdlib.h>
#include <stdio.h>

#ifndef _WIN32
#include <unistd.h>

// name length limit for ptys
#define SLAVENAMELEN 256

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

uart_t::uart_t(simif_t* sim, UARTWIDGET_struct * mmio_addrs, int uartno): endpoint_t(sim)
{
    this->mmio_addrs = mmio_addrs;

    if (uartno == 0) {
        // signal handler so ctrl-c doesn't kill simulation when UART is attached
        // to stdin/stdout
        struct sigaction sigIntHandler;
        sigIntHandler.sa_handler = sighand;
        sigemptyset(&sigIntHandler.sa_mask);
        sigIntHandler.sa_flags = 0;
        sigaction(SIGINT, &sigIntHandler, NULL);
        printf("UART0 is here (stdin/stdout).\n");
        inputfd = STDIN_FILENO;
        outputfd = STDOUT_FILENO;
    } else {
        // for UARTs that are not UART0, use a PTY
        char slavename[SLAVENAMELEN];
        int ptyfd = open("/dev/ptmx", O_RDWR);
        grantpt(ptyfd);
        unlockpt(ptyfd);
        ptsname_r(ptyfd, slavename, SLAVENAMELEN);
        if (!slavename) {
            printf("NULL\n");
            perror("wat");
        }
        printf("UART%d is on PTY: %s\n", uartno, slavename);
        printf("Attach to this UART with sudo screen %s\n", slavename);
        inputfd = ptyfd;
        outputfd = ptyfd;
    }

    // Don't block on reads if there is nothing typed in
    fcntl(inputfd, F_SETFL, fcntl(inputfd, F_GETFL) | O_NONBLOCK);
}

uart_t::~uart_t() {
    free(this->mmio_addrs);
}

void uart_t::send() {
    if (data.in.fire()) {
        write(this->mmio_addrs->in_bits, data.in.bits);
        write(this->mmio_addrs->in_valid, data.in.valid);
    }
    if (data.out.fire()) {
        write(this->mmio_addrs->out_ready, data.out.ready);
    }
}

void uart_t::recv() {
    data.in.ready = read(this->mmio_addrs->in_ready);
    data.out.valid = read(this->mmio_addrs->out_valid);
    if (data.out.valid) {
        data.out.bits = read(this->mmio_addrs->out_bits);
    }
}

void uart_t::tick() {
    data.out.ready = true;
    data.in.valid = false;
    do {
        this->recv();

        if (data.in.ready) {
            char inp;
            int readamt;
            if (specialchar) {
                // send special character (e.g. ctrl-c)
                // for stdin handling
                //
                // PTY should never trigger this
                inp = specialchar;
                specialchar = 0;
                readamt = 1;
            } else {
                // else check if we have input
                readamt = ::read(inputfd, &inp, 1);
            }

            if (readamt > 0) {
                data.in.bits = inp;
                data.in.valid = true;
            }
        }

        if (data.out.fire()) {
            ::write(outputfd, &data.out.bits, 1);
            // always flush to get char-by-char output (not line-buffered)
            // TODO: unnecessary once we've switched to write?
            //fflush(stdout);
        }

        this->send();
        data.in.valid = false;
    } while(data.in.fire() || data.out.fire());
}

#endif // UARTWIDGET_struct_guard
