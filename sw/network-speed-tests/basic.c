#include <stdio.h>
#include <unistd.h>
#include <sys/socket.h>
#include <stdlib.h>
#include <netinet/in.h>
#include <string.h> 
#include <pthread.h>
#include <time.h>

void delay( int numOfSeconds ){

    time_t start = time(NULL);
    printf( "Start: %d\n", start );

    while ( difftime( time(NULL), start + numOfSeconds ) < numOfSeconds ){}

    printf( "End:   %d\n", time(NULL) );

}

int main( int argc, char* argv[] ){
    
    int numSec = 3; 
    
    printf("Start\n");
    // Delay for TIME_SEC*MILLISEC
    delay( numSec );

    printf("End\n");

    exit(EXIT_SUCCESS);
}
