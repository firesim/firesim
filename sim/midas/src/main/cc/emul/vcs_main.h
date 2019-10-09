// See LICENSE for license details.

#ifndef __VCS_MAIN
#define __VCS_MAIN

extern "C" {
extern int vcs_main(int argc, char** argv);
}

struct target_args_t {
  target_args_t(int c, char** v):
    argc(c), argv(v) { }
  int argc;
  char** argv;
};

int target_thread(void *arg) {
  target_args_t* targs = reinterpret_cast<target_args_t*>(arg);
  int argc = targs->argc;
  char** argv = targs->argv;
  delete targs;
  return vcs_main(argc, argv);
}

#endif // __VCS_MAIN
