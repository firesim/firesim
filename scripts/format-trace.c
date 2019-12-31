#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <unistd.h>

struct trace {
	char valid;
	uint64_t iaddr;
};

void print_usage(FILE *stream, const char *name)
{
	fprintf(stream, "Usage: %s [-n <num_traces>] [-h] [<tracefile>]\n", name);
}

int main(int argc, char *argv[])
{
	FILE *f;
	int ntraces = 1, valid_only = 0;
	int i, res, opt;
	uint64_t traceData[8];
	struct trace trace;
	long cycle = 0;

	while ((opt = getopt(argc, argv, "n:hv")) != -1) {
		switch (opt) {
		case 'n':
			ntraces = atol(optarg);
			break;
		case 'v':
			valid_only = 1;
			break;
		case 'h':
			print_usage(stdout, argv[0]);
			exit(EXIT_SUCCESS);
		default:
			print_usage(stderr, argv[0]);
			exit(EXIT_FAILURE);
		}
	}

	if (argc <= optind) {
		f = stdin;
	} else {
		f = fopen(argv[optind], "r");
		if (f == NULL) {
			perror("fopen()");
			exit(EXIT_FAILURE);
		}
	}

	if (ntraces > 7) {
		fprintf(stderr, "Can't have %d > 7 traces\n", ntraces);
		exit(EXIT_FAILURE);
	}

	while ((res = fread(traceData, sizeof(uint64_t), 8, f)) > 0) {
		cycle = traceData[7];

		for (i = 0; i < ntraces; i++) {
			trace.valid = (traceData[i] >> 40) & 1;
			trace.iaddr = traceData[i] & ((1L << 40) - 1);

			if (!valid_only || trace.valid) {
				printf("T%d: %12ld [%d] pc=[%010lx]\n",
					i, cycle, trace.valid, trace.iaddr);
			}
		}
	}

	fclose(f);

	return 0;
}
