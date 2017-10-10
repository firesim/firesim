// See LICENSE for license details.

#ifndef __CONTEXT_H
#define __CONTEXT_H

#include <pthread.h>

class midas_context_t
{
public:
  midas_context_t();
  ~midas_context_t();
  void init(int (*func)(void*), void* arg);
  void switch_to();
  static midas_context_t* current();
private:
  midas_context_t* creator;
  int (*func)(void*);
  void* arg;

  pthread_t thread;
  pthread_mutex_t mutex;
  pthread_cond_t cond;
  volatile int flag;
  static void* wrapper(void*);
};

#endif // __CONTEXT_H
