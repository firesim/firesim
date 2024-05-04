LIBS=-lz -lpthread

OBJS=test.o thread_pool.o mem_pool.o

%.o: %.cc %.h
	g++ -g -o $@ -c $<

main: $(OBJS)
	g++ -g -o $@ $^ $(LIBS)

PHONY: clean
clean:
	rm -f *.o main
