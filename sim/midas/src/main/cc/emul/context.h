#ifndef _EMUL_CONTEXT_H
#define _EMUL_CONTEXT_H

#include <pthread.h>

class pcontext_t final {
public:
  pcontext_t();
  ~pcontext_t();
  void init(void (*func)(void *), void *arg);
  void switch_to();
  static pcontext_t *current();

private:
  pcontext_t *creator;
  void (*func)(void *);
  void *arg;

  pthread_t thread;
  pthread_mutex_t mutex;
  pthread_cond_t cond;
  volatile int flag;
  static void *wrapper(void *);
};

#endif
