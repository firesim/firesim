#ifndef __SHMEMPIPE_H__
#define __SHMEMPIPE_H__

#include <errno.h>
#include <stdio.h>

class ShmemPipe : public BasePipe {
public:
  ShmemPipe(int pipeNo,
            int fromhost_dmatokens_per_transaction,
            int tohost_dmatokens_per_transaction);
  void recv();
  void send();
  void tick_pre();
  void tick();

private:
  int TO_HOST_PER_TRANSACTION_BYTES;
  int FROM_HOST_PER_TRANSACTION_BYTES;

  uint8_t *recvbufs[2];
  uint8_t *sendbufs[2];
  int currentround = 0;
};

ShmemPipe::ShmemPipe(int pipeNo,
                     int fromhost_dmatokens_per_transaction,
                     int tohost_dmatokens_per_transaction)
    : BasePipe(pipeNo) {
#define SHMEM_EXTRABYTES 1
#define SHMEM_NAME_SIZE 120

  this->TO_HOST_PER_TRANSACTION_BYTES =
      tohost_dmatokens_per_transaction * CYCLE_BATCH_SIZE * BIGTOKEN_BYTES;
  this->FROM_HOST_PER_TRANSACTION_BYTES =
      fromhost_dmatokens_per_transaction * CYCLE_BATCH_SIZE * BIGTOKEN_BYTES;

  fprintf(stdout,
          "TO_HOST_PER_TRANSACTION_BYTES: %d\n",
          TO_HOST_PER_TRANSACTION_BYTES);
  fprintf(stdout,
          "FROM_HOST_PER_TRANSACTION_BYTES: %d\n",
          FROM_HOST_PER_TRANSACTION_BYTES);

  // create shared memory regions
  char name[SHMEM_NAME_SIZE];
  int shmemfd;

  char *recvdirection = "t2h";
  char *senddirection = "h2t";

  int ftresult;
  int shm_flags = O_RDWR | O_CREAT | O_TRUNC;

  for (int j = 0; j < 2; j++) {
    snprintf(
        name, SHMEM_NAME_SIZE, "/pipe_%s%03d_%d", recvdirection, _pipeNo, j);
    fprintf(stdout, "opening/creating shmem region\n%s\n", name);
    shmemfd = shm_open(name, shm_flags, S_IRWXU);

    if (shmemfd == -1) {
      perror("shm_open failed");
      abort();
    }

    ftresult =
        ftruncate(shmemfd, TO_HOST_PER_TRANSACTION_BYTES + SHMEM_EXTRABYTES);
    if (ftresult == -1) {
      perror("ftruncate failed");
      abort();
    }

    recvbufs[j] =
        (uint8_t *)mmap(NULL,
                        TO_HOST_PER_TRANSACTION_BYTES + SHMEM_EXTRABYTES,
                        PROT_READ | PROT_WRITE,
                        MAP_SHARED,
                        shmemfd,
                        0);

    if (recvbufs[j] == MAP_FAILED) {
      perror("mmap failed");
      abort();
    }

    memset(recvbufs[j], 0, TO_HOST_PER_TRANSACTION_BYTES + SHMEM_EXTRABYTES);

    fprintf(stdout, "Using slot-id associated shmempipename:\n");
    sprintf(name, "/pipe_%s%03d_%d", senddirection, _pipeNo, j);

    fprintf(stdout, "opening/creating shmem region\n%s\n", name);
    shmemfd = shm_open(name, shm_flags, S_IRWXU);

    if (shmemfd == -1) {
      perror("shm_open failed");
      abort();
    }

    ftresult =
        ftruncate(shmemfd, FROM_HOST_PER_TRANSACTION_BYTES + SHMEM_EXTRABYTES);
    if (ftresult == -1) {
      perror("ftruncate failed");
      abort();
    }

    sendbufs[j] =
        (uint8_t *)mmap(NULL,
                        FROM_HOST_PER_TRANSACTION_BYTES + SHMEM_EXTRABYTES,
                        PROT_READ | PROT_WRITE,
                        MAP_SHARED,
                        shmemfd,
                        0);

    if (sendbufs[j] == MAP_FAILED) {
      perror("mmap failed");
      abort();
    }

    memset(sendbufs[j], 0, FROM_HOST_PER_TRANSACTION_BYTES + SHMEM_EXTRABYTES);
  }

  // setup "current" bufs. tick will swap for shmem passing
  current_input_buf = recvbufs[0];
  current_output_buf = sendbufs[0];
}

void ShmemPipe::send() {
  /* fprintf(stdout, "ShmemPipe::send current_output_buf[%d]\n", currentround);
   */
  /* fflush(stdout); */

  if (((uint64_t *)current_output_buf)[0] == 0xDEADBEEFDEADBEEFL) {
    // if compress flag is set, clear it, this pipe type doesn't care
    // (and in fact, we're writing too much, so stuff later will get confused)
    ((uint64_t *)current_output_buf)[0] = 0L;
  }
  // mark flag to initiate "send"
  current_output_buf[FROM_HOST_PER_TRANSACTION_BYTES] = 1;
}

void ShmemPipe::recv() {
  volatile uint8_t *polladdr =
      current_input_buf + TO_HOST_PER_TRANSACTION_BYTES;
  while (*polladdr == 0) {
    ;
  } // poll

  /* fprintf(stdout, "ShmemPipe::recv[%d] current_input_buf[%d][%d]\n", _pipeNo,
   * currentround, TO_HOST_PER_TRANSACTION_BYTES); */
  /* fflush(stdout); */
}

void ShmemPipe::tick_pre() {
  currentround = (currentround + 1) % 2;
  current_output_buf = sendbufs[currentround];
}

void ShmemPipe::tick() {
  // zero out recv buf flag for next iter
  current_input_buf[TO_HOST_PER_TRANSACTION_BYTES] = 0;

  // swap buf pointers
  current_input_buf = recvbufs[currentround];
}

#endif // __SHMEMPIPE_H__
