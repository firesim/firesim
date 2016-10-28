#ifndef __CONTEXT_H
#define __CONTEXT_H

#include <pthread.h>

class context_t
{
public:
  context_t();
  ~context_t();
  void init(int (*func)(void*), void* arg);
  void switch_to();
  static context_t* current();
private:
  context_t* creator;
  int (*func)(void*);
  void* arg;

  pthread_t thread;
  pthread_mutex_t mutex;
  pthread_cond_t cond;
  volatile int flag;
  static void* wrapper(void*);
};

#endif // __CONTEXT_H
