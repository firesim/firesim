

class ShmemPort : public BasePort {
    public:
        ShmemPort(int portNo);
        void tick();
        void tick_pre();
        void send();
        void recv();
    private:
        uint8_t * recvbufs[2];
        uint8_t * sendbufs[2];
        int currentround = 0;
};

ShmemPort::ShmemPort(int portNo) : BasePort(portNo) {
#define SHMEM_EXTRABYTES 1

    // create shared memory regions
    char name[100];
    int shmemfd;
    for (int j = 0; j < 2; j++) {
        sprintf(name, "/port_nts%d_%d", _portNo, j);
        printf("opening/creating shmem region %s\n", name);
        shmemfd = shm_open(name, O_RDWR | O_CREAT | O_TRUNC, S_IRWXU);
        ftruncate(shmemfd, BUFSIZE_BYTES+SHMEM_EXTRABYTES);
        recvbufs[j] = (uint8_t*)mmap(NULL, BUFSIZE_BYTES+SHMEM_EXTRABYTES, PROT_READ | PROT_WRITE, MAP_SHARED, shmemfd,0);
        memset(recvbufs[j], 0, BUFSIZE_BYTES+SHMEM_EXTRABYTES);

        sprintf(name, "/port_stn%d_%d", _portNo, j);
        printf("opening/creating shmem region %s\n", name);
        shmemfd = shm_open(name, O_RDWR | O_CREAT | O_TRUNC, S_IRWXU);
        ftruncate(shmemfd, BUFSIZE_BYTES+SHMEM_EXTRABYTES);
        sendbufs[j] = (uint8_t*)mmap(NULL, BUFSIZE_BYTES+SHMEM_EXTRABYTES, PROT_READ | PROT_WRITE, MAP_SHARED, shmemfd,0);
        memset(sendbufs[j], 0, BUFSIZE_BYTES+SHMEM_EXTRABYTES);
    }

    // setup "current" bufs. tick will swap for shmem passing
    current_input_buf = recvbufs[0];
    current_output_buf = sendbufs[0];
}

void ShmemPort::send() {
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
