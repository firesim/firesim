#include "context.h"
#include <stdlib.h>
#include <stdexcept>

static __thread context_t* cur = NULL;

context_t::context_t()
  : creator(NULL), func(NULL), arg(NULL),
    mutex(PTHREAD_MUTEX_INITIALIZER),
    cond(PTHREAD_COND_INITIALIZER), flag(0)
{
}

context_t* context_t::current()
{
  if (cur == NULL)
  {
    cur = new context_t;
    cur->thread = pthread_self();
    cur->flag = 1;
  }
  return cur;
}

void* context_t::wrapper(void* a)
{
  context_t* ctx = static_cast<context_t*>(a);
  cur = ctx;
  ctx->creator->switch_to();

  ctx->func(ctx->arg);
  return NULL;
}

void context_t::init(int (*f)(void*), void* a)
{
  func = f;
  arg = a;
  creator = current();

  if (flag != 0)
    throw std::logic_error("flag != 0 in context_t::init");

  pthread_mutex_lock(&creator->mutex);
  creator->flag = 0;
  if (pthread_create(&thread, NULL, &context_t::wrapper, this) != 0)
    abort();
  pthread_detach(thread);
  while (!creator->flag)
    pthread_cond_wait(&creator->cond, &creator->mutex);
  pthread_mutex_unlock(&creator->mutex);
}

context_t::~context_t()
{
  if (this == cur)
    throw std::logic_error("this == cur in context_t::~context_t()");
}

void context_t::switch_to()
{
  if (this == cur)
    throw std::logic_error("this == cur in context_t::~context_t()");
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
