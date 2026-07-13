
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

#define BUF_SIZE 1024

int main(int argc, char *argv[]) {
  if (argc != 5) {
    printf("There must be exactly 4 arguments! Got %d\n", argc);
    return -1;
  }

  char * cmd_lookup[] = {"NOP", "NOP", "ACT", "ACT", "PRE", "PRE", "RD", "RDA", "WR", "WRA", "REF", "REF"};

  // Output files
  int ranks = atoi(argv[1]);
  FILE * outfp[ranks];
  for (int i = 0; i < ranks; i++) {
    char filename[100];
    sprintf(filename, "%s%d.trace.csv", argv[3], i);
    outfp[i] = fopen(filename, "w");
  }

  FILE *fp;
  char buf[BUF_SIZE];
  fp = fopen(argv[2], "r");
  if (fp == NULL) {
    printf("ERROR! unable to open file %s\n", argv[2]);
    return -1;
  }
  // Skip first line
  fgets(buf, sizeof(buf), fp);

  uint64_t raw_cycle, prev_raw_cycle = 0, cycle;
  uint32_t cmd, bank, rank, row, auto_pre;
  int fscan_count;
  char *cycle_p, *cmd_p, *bank_p, *rank_p, *autoPRE_p, *cmd_str;

  uint64_t roll_over_count = 0;
  const uint64_t ROLL_OVER_CONST = 4294967296L;
  while (fgets(buf, sizeof(buf), fp) != NULL) {
    char * c = buf;
    char * d = buf;
    // *********** Quick split **********
    // cycle_p
    while (*c != ',' && *c != '\n') c++;
    if (*c == '\n') break;
    cycle_p = d;
    *c = '\0';
    d = c+1;
    // cmd_p
    while (*c != ',' && *c != '\n') c++;
    if (*c == '\n') break;
    cmd_p = d;
    *c = '\0';
    d = c+1;
    // bank_p
    while (*c != ',' && *c != '\n') c++;
    if (*c == '\n') break;
    bank_p = d;
    *c = '\0';
    d = c+1;
    // bank_p
    while (*c != ',' && *c != '\n') c++;
    if (*c == '\n') break;
    rank_p = d;
    *c = '\0';
    d = c+1;
    // skip row
    while (*c != ',' && *c != '\n') c++;
    if (*c == '\n') break;
    *c = '\0';
    d = c+1;
    // skip
    while (*c != ',' && *c != '\n') c++;
    *c = '\0';
    autoPRE_p = d;
    d = c+1;

    raw_cycle = atol(cycle_p);
    if (raw_cycle < prev_raw_cycle) {
      printf("Roll over from %ld to %ld\n", prev_raw_cycle, raw_cycle);
      roll_over_count++;
    }
    cycle = raw_cycle + (roll_over_count * ROLL_OVER_CONST);
    prev_raw_cycle = raw_cycle;
    auto_pre = atoi(autoPRE_p);
    cmd = atoi(cmd_p);
    bank = atoi(bank_p);
    rank = atoi(rank_p);

    cmd_str = cmd_lookup[cmd*2 + auto_pre];

    fprintf(outfp[rank], "%ld,%s,%d\n", cycle, cmd_str, bank);
  }
  fclose(fp);
  for (int i = 0; i < ranks; i++) {
    fclose(outfp[i]);
  }

  return 0;
}

