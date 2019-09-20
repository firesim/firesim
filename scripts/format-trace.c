#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <unistd.h>

struct trace {
	char valid;
	uint64_t iaddr;
	uint32_t insn;
	char priv;
	char exception;
	char interrupt;
	char cause;
	uint64_t tval;
};

uint64_t pull_bits(size_t *pos, uint64_t *traceData, int nbits)
{
	size_t idx = (*pos) / 64;
	size_t shift = (*pos) % 64;
	size_t left = 64 - shift;
	uint64_t data, mask;

	data = traceData[idx] >> shift;
	mask = (1UL << nbits) - 1;

	if (nbits > left) {
		data |= (traceData[idx+1] << left);
	}

	*pos += nbits;

	return data & mask;
}

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

	while ((res = fread(traceData, sizeof(uint64_t), 8, f)) > 0) {
		size_t pos = 0;

		for (i = 0; i < ntraces; i++) {
			trace.tval = pull_bits(&pos, traceData, 40);
			trace.cause = pull_bits(&pos, traceData, 8);
			trace.interrupt = pull_bits(&pos, traceData, 1);
			trace.exception = pull_bits(&pos, traceData, 1);
			trace.priv = pull_bits(&pos, traceData, 3);
			trace.insn = pull_bits(&pos, traceData, 32);
			trace.iaddr = pull_bits(&pos, traceData, 40);
			trace.valid = pull_bits(&pos, traceData, 1);

			if (!valid_only || trace.valid) {
				printf("T%d: %12ld [%d] pc=[%010lx] [exc %d] [int %d] [cause %d] DASM(%08x)\n",
					i, cycle, trace.valid, trace.iaddr,
					trace.exception, trace.interrupt, trace.cause, trace.insn);
			}
		}

		cycle++;
	}

	fclose(f);

	return 0;
}
