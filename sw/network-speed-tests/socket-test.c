#include <stdio.h>
#include <unistd.h>
#include <sys/socket.h>
#include <stdlib.h>
#include <netinet/in.h>
#include <string.h> 
#include <pthread.h>
#include <time.h>

char *hello = "123456789ABCDEFDEADBEEF This is a test string being sent back and forth from the client <-> server";

void delay( int numOfSeconds ){
    time_t start = time(NULL);
    time_t curr = time(NULL);

    while ( difftime( curr, start + numOfSeconds ) < numOfSeconds ){
        printf("CurrTime: %d\n", curr);
        curr = time(NULL);
    }
}

void fail( char * buffer ){
    printf( buffer );
    printf( "\n" );
    exit( EXIT_FAILURE );
}

void *server( void *vargp ){
    
    int server_handle;
    int new_socket;
    int valread;
    struct sockaddr_in address;
    int addrlen = sizeof(address);
    char buffer[1024] = {0};
    int* portNum = (int*)vargp;

    printf( "Started server\n" );

    // Creating socket handle 
    if ((server_handle = socket(AF_INET, SOCK_STREAM, 0)) == 0){
        fail("Failed to make server socket");
    }

    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons( 8080 );

    // Attach socket to the port
    if (bind(server_handle, (struct sockaddr *)&address,sizeof(address))<0){
        fail("Failed to bind to server port");
    }

    // Wait for a connect request
    if (listen(server_handle, 3) < 0){
        fail("Failed to listen to the client connection");
    }

    // Connect to the connection
    if ((new_socket = accept(server_handle, (struct sockaddr *)&address,(socklen_t*)&addrlen))<0){
        fail("Failed to accept the client connection from server");
    }

    printf( "Connection to client established\n" );

    while(1){
        valread = read( new_socket, buffer, 1024);
        if ( valread < 0 ){
            printf( "Something not read correctly\n" );
        }
        else{
            if( strcmp( hello, buffer ) != 0 ){
                fail("Error on server");
            }
        }

        // Send the same information back to the client
        send(new_socket , buffer, strlen(buffer) , 0 );
    }
}

void *client( void *vargp ){
    struct sockaddr_in address;
    int client_handle = 0;
    int valread;
    struct sockaddr_in serv_addr;
    char buffer[1024] = {0};
    char *ipAddr = (char*)vargp;

    printf( "Client started\n" );

    // Create a client socket
    if ((client_handle = socket(AF_INET, SOCK_STREAM, 0)) < 0){
        fail("Client socket creation failed");
    }

    // Clear the serv_addr construct
    memset(&serv_addr, '0', sizeof(serv_addr));

    serv_addr.sin_family = AF_INET;
    serv_addr.sin_port = htons(8080);

    printf( "IP Addr passed in: %s %x\n", (char*)vargp, vargp);
    // Convert IPv4 and IPv6 addresses from text to binary form
    if(inet_pton(AF_INET, ipAddr, &serv_addr.sin_addr)<=0){
        fail("Invalid IP address" );
    }

    if (connect(client_handle, (struct sockaddr *)&serv_addr, sizeof(serv_addr)) < 0){
        fail("Connection failed");
    }

    printf( "Connected to server socket\n" );

    while(1){
        send(client_handle, hello , strlen(hello) , 0 );
        valread = read( client_handle, buffer, 1024);
        if ( valread < 0 ){
            printf( "Something not read correctly\n" );
        }
        else{
            if( strcmp( hello, buffer ) != 0 ){
                fail("Error on client");
            }
        }
    }
}

/* This is a simple socket test that connects from the client to the server
 * and sends the same packet back and forth
 */
int main( int argc, char* argv[] ){
    
    if( argc < 4 ){
        fail( "Need to insert PORTNUM then IPADDR then TIME_SEC" );
    }

    int portNum = atoi(argv[1]);
    int numSec = atoi(argv[3]);
    pthread_t tid1, tid2;

    // Create both the client and server threads
    pthread_create(&tid1, NULL, server, (void*)&portNum);
    pthread_create(&tid2, NULL, client, (void*)argv[2]);
    
    // Delay for TIME_SEC*MILLISEC
    delay( numSec );

    // TODO: Does this kill the processes?
    pthread_cancel(tid1);
    pthread_cancel(tid2);

    exit(EXIT_SUCCESS);
}
