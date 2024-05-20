#include <algorithm>
#include <arpa/inet.h>
#include <cstdlib>
#include <functional>
#include <omp.h>
#include <queue>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

//#define CAPTURE
#define IGNORE_PRINTF

#ifdef IGNORE_PRINTF
#define printf(fmt, ...) (0)
#endif

// THIS IS SET BY A COMMAND LINE ARGUMENT. DO NOT CHANGE IT HERE.
int CYCLE_BATCH_SIZE = 0;

// pull in # clients config
#define NUMPARTITIONSCONFIG
#include "partitionconfig.h"
#undef NUMPARTITIONSCONFIG

// DO NOT TOUCH
#define BIGTOKEN_BYTES (64)

#include "basepipe.h"
#include "shmempipe.h"

#define NUM_CYCLES (CYCLE_BATCH_SIZE)

BasePipe *pipes[NUMPIPES];

static FILE *capture;

// FIXME : We can probably just shuffle pointers around instead of copying
// memory
void do_fast_switching_raw_buffer() {
  /* fprintf(stdout, "do_fast_switching_raw_buffer()\n"); */
  for (int pipe = 0; pipe < NUMPIPES; pipe++) {
    int dest_pipe = DESTINATION_PIPE_IDX[pipe];
    int buffersize =
        BIGTOKEN_BYTES * NUM_CYCLES * TOHOST_DMATOKENS_PER_TRANSACTION[pipe];
    /* fprintf(stdout, "pipe[%d] -> pipe[%d], %d bytes\n", pipe, dest_pipe,
     * buffersize); */
    memcpy(pipes[dest_pipe]->current_output_buf,
           pipes[pipe]->current_input_buf,
           buffersize);
  }
}

int main(int argc, char *argv[]) {
  if (argc < 2) {
    // if insufficient args, error out
    fprintf(stdout, "usage: ./partitionhub CYCLE_BATCH_SIZE\n");
    fprintf(stdout, "insufficient args provided\n.");
    fprintf(stdout, "CYCLE_BATCH_SIZE should be provided in cycles.\n");
    exit(1);
  }

  CYCLE_BATCH_SIZE = atoi(argv[1]);

  for (int pipe = 0; pipe < NUMPIPES; pipe++) {
    fprintf(stdout,
            "BUFSIZE_BYTES_%d_TO_%d: %d\n",
            pipe,
            DESTINATION_PIPE_IDX[pipe],
            TOHOST_DMATOKENS_PER_TRANSACTION[pipe] * BIGTOKEN_BYTES *
                NUM_CYCLES);
  }

  fprintf(stdout, "Using link latency: %d\n", CYCLE_BATCH_SIZE);

  omp_set_num_threads(
      NUMPIPES); // we parallelize over pipes, so max threads = # pipes

#define PIPESETUPCONFIG
#include "partitionconfig.h"
#undef PIPESETUPCONFIG

  fprintf(stdout, "partitionconfig included\n");

  while (true) {

    // handle receives. these are blocking per pipe
#pragma omp parallel for
    for (int pipe = 0; pipe < NUMPIPES; pipe++) {
      /* fprintf(stdout, "receiving pipe %d\n", pipe); */
      pipes[pipe]->recv();
    }

    do_fast_switching_raw_buffer();

    // handle sends
#pragma omp parallel for
    for (int pipe = 0; pipe < NUMPIPES; pipe++) {
      /* fprintf(stdout, "sending pipe %d\n", pipe); */
      pipes[pipe]->send();
    }

#pragma omp parallel for
    for (int pipe = 0; pipe < NUMPIPES; pipe++) {
      /* fprintf(stdout, "tick_pre pipe %d\n", pipe); */
      pipes[pipe]->tick_pre();
    }

    // some pipes need to handle extra stuff after each iteration
    // e.g. shmem pipes swapping shared buffers
#pragma omp parallel for
    for (int pipe = 0; pipe < NUMPIPES; pipe++) {
      pipes[pipe]->tick();
    }
  }
}
