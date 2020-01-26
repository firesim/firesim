// See LICENSE for license details.

#include "midas_context.h"
#include <stdlib.h>
#include <cassert>

static __thread midas_context_t* cur = NULL;

midas_context_t::midas_context_t()
  : creator(NULL), func(NULL), arg(NULL),
    mutex(PTHREAD_MUTEX_INITIALIZER),
    cond(PTHREAD_COND_INITIALIZER), flag(0)
{
}

midas_context_t* midas_context_t::current()
{
  if (cur == NULL)
  {
    cur = new midas_context_t;
    cur->thread = pthread_self();
    cur->flag = 1;
  }
  return cur;
}

void* midas_context_t::wrapper(void* a)
{
  midas_context_t* ctx = static_cast<midas_context_t*>(a);
  cur = ctx;
  ctx->creator->switch_to();

  ctx->func(ctx->arg);
  return NULL;
}

void midas_context_t::init(int (*f)(void*), void* a)
{
  func = f;
  arg = a;
  creator = current();

  assert(flag == 0);

  pthread_mutex_lock(&creator->mutex);
  creator->flag = 0;
  if (pthread_create(&thread, NULL, &midas_context_t::wrapper, this) != 0)
    abort();
  pthread_detach(thread);
  while (!creator->flag)
    pthread_cond_wait(&creator->cond, &creator->mutex);
  pthread_mutex_unlock(&creator->mutex);
}

midas_context_t::~midas_context_t()
{
  assert(this != cur);
}

void midas_context_t::switch_to()
{
  assert(this != cur);
  cur->flag = 0;
  this->flag = 1;
  pthread_mutex_lock(&this->mutex);
  pthread_cond_signal(&this->cond);
  pthread_mutex_unlock(&this->mutex);
  pthread_mutex_lock(&cur->mutex);
  while (!cur->flag)
    pthread_cond_wait(&cur->cond, &cur->mutex);
  pthread_mutex_unlock(&cur->mutex);
}
