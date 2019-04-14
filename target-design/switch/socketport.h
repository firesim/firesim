#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>

class SocketClientPort : public BasePort {
    public:
        SocketClientPort(int portNo, char * serverip, int hostport);
        void tick();
        void tick_pre();
        void send();
        void recv();
    private:
        int clientsocket;
};

SocketClientPort::SocketClientPort(int portNo, char * serverip, int hostport) : BasePort(portNo, false) {

    struct sockaddr_in serv_addr;

    // connect the uplink socket
    fprintf(stdout, "ClientSocketPort portNo %d connecting to uplink switch %s\n", portNo, serverip);
    // this is a client
    clientsocket = socket(AF_INET, SOCK_STREAM, 0);
    if (clientsocket < 0) {
        fprintf(stdout, "SOCK FAILED!\n");
        exit(1);
    }
    memset(&serv_addr, '0', sizeof(serv_addr));
    serv_addr.sin_family = AF_INET;
    serv_addr.sin_port = htons(hostport);

    if (inet_pton(AF_INET, serverip, &serv_addr.sin_addr) <= 0) {
        fprintf(stdout, "INVALID ADDR\n");
        exit(1);
    }

    while (connect(clientsocket, (struct sockaddr *)&serv_addr, sizeof(serv_addr)) < 0) {
        fprintf(stdout, "CONNECTION FAILED, retrying in 1s.\n");
        sleep(1);
    }


    // setup "current" bufs. tick will swap for shmem passing
    current_input_buf = (uint8_t*)calloc(BUFSIZE_BYTES, 1);
    current_output_buf = (uint8_t*)calloc(BUFSIZE_BYTES, 1);;
}

void SocketClientPort::send() {
    if (((uint64_t*)current_output_buf)[0] == 0xDEADBEEFDEADBEEFL) {
//        printf("sending compressed\n");
#define COMPRESS_NUM_BYTES (8)
        int amtsent = ::send(clientsocket, current_output_buf, COMPRESS_NUM_BYTES, 0);
        if (amtsent != COMPRESS_NUM_BYTES) { printf("SOCKETPORT SEND ERROR\n"); exit(1); }
    } else {
        int amtsent = ::send(clientsocket, current_output_buf, BUFSIZE_BYTES, 0);
        if (amtsent != BUFSIZE_BYTES) { printf("SOCKETPORT SEND ERROR\n"); exit(1); }
    }
}

void SocketClientPort::recv() {
    int amtread = 0;
    while (amtread < COMPRESS_NUM_BYTES) {
        amtread += ::recv(clientsocket, current_input_buf + amtread,
                COMPRESS_NUM_BYTES - amtread, 0);
    }
    if (((uint64_t*)current_input_buf)[0] == 0xDEADBEEFDEADBEEFL) {
//        printf("recv compressed\n");
        memset(current_input_buf, 0x0, BUFSIZE_BYTES);
        return;
    }

    while (amtread < BUFSIZE_BYTES) {
        amtread += ::recv(clientsocket, current_input_buf + amtread,
                BUFSIZE_BYTES - amtread, 0);
    }
}

void SocketClientPort::tick() {
    // does nothing in this port
}

void SocketClientPort::tick_pre() {
    // does nothing in this port
}


class SocketServerPort : public BasePort {
    public:
        SocketServerPort(int portNo, int hostport);
        void tick();
        void tick_pre();
        void send();
        void recv();
    private:
        int serversocket;
};

SocketServerPort::SocketServerPort(int portNo, int hostport) : BasePort(portNo, false) {
    int server_fd;
    struct sockaddr_in address;
    int opt = 1;
    int addrlen = sizeof(address);

    // Creating socket file descriptor
    if ((server_fd = socket(AF_INET, SOCK_STREAM, 0)) == 0) {
        perror("socket failed");
        exit(EXIT_FAILURE);
    }

    // Forcefully attaching socket to the port
    if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR | SO_REUSEPORT,
                                                  &opt, sizeof(opt))) {
        perror("setsockopt");
        exit(EXIT_FAILURE);
    }
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(hostport);

    // Forcefully attaching socket to the port
    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address))<0) {
        perror("bind failed");
        exit(EXIT_FAILURE);
    }
    if (listen(server_fd, 3) < 0) {
        perror("listen");
        exit(EXIT_FAILURE);
    }

    fprintf(stdout, "waiting for clients to connect\n");
    if ((serversocket = accept(server_fd, (struct sockaddr *)&address, (socklen_t*)&addrlen))<0) {
        perror("accept");
        exit(EXIT_FAILURE);
    } else {
        fprintf(stdout, "SocketServerPort %d accepted client\n", portNo);
    }

    // setup "current" bufs. tick will swap for shmem passing
    current_input_buf = (uint8_t*)calloc(BUFSIZE_BYTES, 1);
    current_output_buf = (uint8_t*)calloc(BUFSIZE_BYTES, 1);;
}

void SocketServerPort::send() {
    if (((uint64_t*)current_output_buf)[0] == 0xDEADBEEFDEADBEEFL) {
//        printf("sending compressed\n");
#define COMPRESS_NUM_BYTES (8)
        int amtsent = ::send(serversocket, current_output_buf, COMPRESS_NUM_BYTES, 0);
        if (amtsent != COMPRESS_NUM_BYTES) { printf("SOCKETPORT SEND ERROR\n"); exit(1); }
    } else {
        int amtsent = ::send(serversocket, current_output_buf, BUFSIZE_BYTES, 0);
        if (amtsent != BUFSIZE_BYTES) { printf("SOCKETPORT SEND ERROR\n"); exit(1); }
    }
}

void SocketServerPort::recv() {
    int amtread = 0;
    while (amtread < COMPRESS_NUM_BYTES) {
        amtread += ::recv(serversocket, current_input_buf + amtread,
                COMPRESS_NUM_BYTES - amtread, 0);
    }
    if (((uint64_t*)current_input_buf)[0] == 0xDEADBEEFDEADBEEFL) {
//        printf("recv compressed\n");
        memset(current_input_buf, 0x0, BUFSIZE_BYTES);
        return;
    }

    while (amtread < BUFSIZE_BYTES) {
        amtread += ::recv(serversocket, current_input_buf + amtread,
                BUFSIZE_BYTES - amtread, 0);
    }
}

void SocketServerPort::tick() {
    // does nothing in this port
}

void SocketServerPort::tick_pre() {
    // does nothing in this port
}

