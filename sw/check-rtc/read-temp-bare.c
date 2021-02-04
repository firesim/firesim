#include <stdlib.h>
#include <stdio.h>
#include "util.h"
#include "mmio.h"

#define TEMP_ADDR 0x102000

int main(void)
{
    uint8_t temp = reg_read8(TEMP_ADDR);
	printf("Temperature:  %ld C\n", temp/2);
	return 0;
}
