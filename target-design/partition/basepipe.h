#ifndef __BASEPIPE_H__
#define __BASEPIPE_H__

#include <stdlib.h>

// NOTE
// - Currently there is no need to use a abstract class for the pipe
// implementation. Just for future...
class BasePipe {
public:
  BasePipe(int pipeNo);

  virtual void recv() = 0;
  virtual void send() = 0;
  virtual void tick_pre() = 0;
  virtual void tick() = 0;

  uint8_t *current_input_buf;  // current input buf
  uint8_t *current_output_buf; // current output buf

protected:
  int _pipeNo;
};

BasePipe::BasePipe(int pipeNo) : _pipeNo(pipeNo) {}

#endif //__BASEPIPE_H__
