#include "context.h"
#include <assert.h>
#include <sched.h>
#include <stdlib.h>

static __thread pcontext_t *cur;

pcontext_t::pcontext_t()
    : creator(NULL), func(NULL), arg(NULL), mutex(PTHREAD_MUTEX_INITIALIZER),
      cond(PTHREAD_COND_INITIALIZER), flag(0) {}

void *pcontext_t::wrapper(void *a) {
  pcontext_t *ctx = static_cast<pcontext_t *>(a);
  cur = ctx;
  ctx->creator->switch_to();

  ctx->func(ctx->arg);
  return NULL;
}

void pcontext_t::init(void (*f)(void *), void *a) {
  func = f;
  arg = a;
  creator = current();

  assert(flag == 0);

  pthread_mutex_lock(&creator->mutex);
  creator->flag = 0;
  if (pthread_create(&thread, NULL, &pcontext_t::wrapper, this) != 0)
    abort();
  pthread_detach(thread);
  while (!creator->flag)
    pthread_cond_wait(&creator->cond, &creator->mutex);
  pthread_mutex_unlock(&creator->mutex);
}

pcontext_t::~pcontext_t() { assert(this != cur); }

void pcontext_t::switch_to() {
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

pcontext_t *pcontext_t::current() {
  if (cur == NULL) {
    cur = new pcontext_t;
    cur->thread = pthread_self();
    cur->flag = 1;
  }
  return cur;
}
