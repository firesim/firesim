#include <errno.h>

class ShmemPort : public BasePort {
    public:
        ShmemPort(int portNo, char * shmemportname, bool uplink);
        void tick();
        void tick_pre();
        void send();
        void recv();
    private:
        uint8_t * recvbufs[2];
        uint8_t * sendbufs[2];
        int currentround = 0;
};

ShmemPort::ShmemPort(int portNo, char * shmemportname, bool uplink) : BasePort(portNo, !uplink) {
#define SHMEM_EXTRABYTES 1

    // create shared memory regions
    char name[100];
    int shmemfd;

    char * recvdirection;
    char * senddirection;

    int ftresult;

    int shm_flags;
    if (uplink) {
        // uplink should not truncate on SHM_OPEN
        shm_flags = O_RDWR /*| O_CREAT*/;
    } else {
        shm_flags = O_RDWR | O_CREAT | O_TRUNC;
    }

    if (uplink) {
        fprintf(stdout, "Uplink Port\n");
        recvdirection = "stn";
        senddirection = "nts";
    } else {
        fprintf(stdout, "Downlink Port\n");
        recvdirection = "nts";
        senddirection = "stn";
    }

    for (int j = 0; j < 2; j++) {
        if (shmemportname) {
            fprintf(stdout, "Using non-slot-id associated shmemportname:\n");
            sprintf(name, "/port_%s%s_%d", recvdirection, shmemportname, j);
        } else {
            fprintf(stdout, "Using slot-id associated shmemportname:\n");
            sprintf(name, "/port_%s%d_%d", recvdirection, _portNo, j);
        }
        fprintf(stdout, "opening/creating shmem region\n%s\n", name);
        shmemfd = shm_open(name, shm_flags, S_IRWXU);

        while (shmemfd == -1) {
            perror("shm_open failed");
            if (uplink) {
                fprintf(stdout, "retrying in 1s...\n");
                sleep(1);
                shmemfd = shm_open(name, shm_flags, S_IRWXU);
            } else {
                abort();
            }
        }

        if (!uplink) {
            ftresult = ftruncate(shmemfd, BUFSIZE_BYTES+SHMEM_EXTRABYTES);
            if (ftresult == -1) {
                perror("ftruncate failed");
                abort();
            }
        }

        recvbufs[j] = (uint8_t*)mmap(NULL, BUFSIZE_BYTES+SHMEM_EXTRABYTES, PROT_READ | PROT_WRITE, MAP_SHARED, shmemfd,0);

        if (recvbufs[j] == MAP_FAILED) {
            perror("mmap failed");
            abort();
        }

        if (!uplink) {
            memset(recvbufs[j], 0, BUFSIZE_BYTES+SHMEM_EXTRABYTES);
        }

        if (shmemportname) {
            fprintf(stdout, "Using non-slot-id associated shmemportname:\n");
            sprintf(name, "/port_%s%s_%d", senddirection, shmemportname, j);
        } else {
            fprintf(stdout, "Using slot-id associated shmemportname:\n");
            sprintf(name, "/port_%s%d_%d", senddirection, _portNo, j);
        }
        fprintf(stdout, "opening/creating shmem region\n%s\n", name);
        shmemfd = shm_open(name, shm_flags, S_IRWXU);

        while (shmemfd == -1) {
            perror("shm_open failed");
            if (uplink) {
                fprintf(stdout, "retrying in 1s...\n");
                sleep(1);
                shmemfd = shm_open(name, shm_flags, S_IRWXU);
            } else {
                abort();
            }
        }

        if (!uplink) {
            ftresult = ftruncate(shmemfd, BUFSIZE_BYTES+SHMEM_EXTRABYTES);
            if (ftresult == -1) {
                perror("ftruncate failed");
                abort();
            }
        }

        sendbufs[j] = (uint8_t*)mmap(NULL, BUFSIZE_BYTES+SHMEM_EXTRABYTES, PROT_READ | PROT_WRITE, MAP_SHARED, shmemfd,0);

        if (sendbufs[j] == MAP_FAILED) {
            perror("mmap failed");
            abort();
        }

        if (!uplink) {
            memset(sendbufs[j], 0, BUFSIZE_BYTES+SHMEM_EXTRABYTES);
        }
    }

    // setup "current" bufs. tick will swap for shmem passing
    current_input_buf = recvbufs[0];
    current_output_buf = sendbufs[0];
}

void ShmemPort::send() {
    if (((uint64_t*)current_output_buf)[0] == 0xDEADBEEFDEADBEEFL) {
        // if compress flag is set, clear it, this port type doesn't care
        // (and in fact, we're writing too much, so stuff later will get confused)
        ((uint64_t*)current_output_buf)[0] = 0L;
    }
    // mark flag to initiate "send"
    current_output_buf[BUFSIZE_BYTES] = 1;
}

void ShmemPort::recv() {
    volatile uint8_t * polladdr = current_input_buf + BUFSIZE_BYTES;
    while (*polladdr == 0) { ; } // poll
}

void ShmemPort::tick_pre() {
    currentround = (currentround + 1) % 2;
    current_output_buf = sendbufs[currentround];
}

void ShmemPort::tick() {
    // zero out recv buf flag for next iter
    current_input_buf[BUFSIZE_BYTES] = 0;

    // swap buf pointers
    current_input_buf = recvbufs[currentround];
}
