#include "debug_api.h"
#include <stack>
class Stack_t: debug_api_t
{
public:
  Stack_t(int size_): debug_api_t("Stack"), size(size_) {}
  void run() {
    std::stack<uint32_t> stack;
    uint32_t nextDataOut = 0; 
    uint32_t dataOut = 0; 
    for (int i = 0 ; i < 16 ; i++) {
      uint32_t enable = rand_next(2);
      uint32_t push   = rand_next(2);
      uint32_t pop    = rand_next(2);
      uint32_t dataIn = rand_next(256);
  
      if (enable) {
        dataOut = nextDataOut;
        if (push && stack.size() < size) {
          stack.push(dataIn);
        } else if (pop && stack.size() > 0) {
          stack.pop();
        }
        if (stack.size()) {
          nextDataOut = stack.top();
        }
      }
      poke("Stack.io_pop",  pop);
      poke("Stack.io_push", push);
      poke("Stack.io_en",   enable);
      poke("Stack.io_dataIn", dataIn);
      step(1);
      expect("Stack.io_dataOut", dataOut);
    } 
  }
private:
  int size;
};

int main() 
{
  Stack_t Stack(8);
  Stack.run();
  return 0;
}
