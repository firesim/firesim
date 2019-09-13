//See LICENSE for license details.

#ifndef __SHIFT_REGISTER_H
#define __SHIFT_REGISTER_H

template <class T>
class ShiftRegister {
  private:
    std::queue<T> fifo;

  public:
    ShiftRegister(size_t delay, T initial_value) {
      for (size_t i = 0; i < delay; i++)
        fifo.push(initial_value);
    }

    T current() { return fifo.front(); }
    void step() { fifo.pop(); }
    void enqueue(T next) { fifo.push(next); }

};

#endif  //__SHIFT_REGISTER_H
