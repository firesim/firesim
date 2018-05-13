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

uart_t::uart_t(simif_t* sim, AddressMap addr_map, char * slotid, char subslotid): endpoint_t(sim, addr_map)
{
#ifndef _WIN32
#define UART_DEVNAME_BYTES 80
    if (!slotid) {
      fprintf(stderr, "Slot ID not specified. Assuming stdin0\n");
      slotid = "0";
    }
    if (!subslotid) {
      fprintf(stderr, "Sub-Slot ID not specified. Assuming stdin%s_0\n",slotid);
      subslotid = '0';
    }

    char stdinname_p[UART_DEVNAME_BYTES+1];
    char stdinname[UART_DEVNAME_BYTES+1];
    stdinname_p[0] = '\0';
    strncat(stdinname_p, "firesim_stdin_sub", UART_DEVNAME_BYTES);
    sprintf(stdinname, "%s_%c", stdinname_p, subslotid);
    stdin_file=fopen(stdinname, "w+");
    stdin_desc=fileno(stdin_file);


    char stdoutname_p[UART_DEVNAME_BYTES+1];
    char stdoutname[UART_DEVNAME_BYTES+1];
    stdoutname_p[0] = '\0';
    strncat(stdoutname_p, "firesim_stdout_sub", UART_DEVNAME_BYTES);
    sprintf(stdoutname, "%s_%c", stdoutname_p, subslotid);
    stdout_file=fopen(stdoutname, "w+");
    stdout_desc=fileno(stdout_file);

    // Don't block on stdin reads if there is nothing typed in
    fcntl(stdin_desc, F_SETFL, fcntl(STDIN_FILENO, F_GETFL) | O_NONBLOCK);

    // signal handler so ctrl-c doesn't kill simulation
    struct sigaction sigIntHandler;
    sigIntHandler.sa_handler = sighand;
    sigemptyset(&sigIntHandler.sa_mask);
    sigIntHandler.sa_flags = 0;
    sigaction(SIGINT, &sigIntHandler, NULL);
#endif
}



uart_t::~uart_t()
{
  fclose(stdin_file);
  fclose(stdout_file);
}


void uart_t::send() {
    if (data.in.fire()) {
        write("in_bits", data.in.bits);
        write("in_valid", data.in.valid);
    }
    if (data.out.fire()) {
        write("out_ready", data.out.ready);
    }
}

void uart_t::recv() {
    data.in.ready = read("in_ready");
    data.out.valid = read("out_valid");
    if (data.out.valid) {
        data.out.bits = read("out_bits");
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
                readamt = ::read(stdin_desc, &inp, 1);
            }

            if (readamt > 0) {
                data.in.bits = inp;
                data.in.valid = true;
            }
        }
#endif

        if (data.out.fire()) {
            fprintf(stdout_file, "%c", data.out.bits);
            // always flush to get char-by-char output (not line-buffered)
            fflush(stdout_file);
        }

        this->send();
        data.in.valid = false;
    } while(data.in.fire() || data.out.fire());
}
