 // THIS FILE IS MACHINE GENERATED. SEE emitconfig.py
    
#ifdef NUMCLIENTSCONFIG
#define NUMPORTS 8
#endif
#ifdef PORTSETUPCONFIG
ports[0] = new ShmemPort(0);
ports[1] = new ShmemPort(1);
ports[2] = new ShmemPort(2);
ports[3] = new ShmemPort(3);
ports[4] = new ShmemPort(4);
ports[5] = new ShmemPort(5);
ports[6] = new ShmemPort(6);
ports[7] = new ShmemPort(7);

#endif

#ifdef MACPORTSCONFIG
uint16_t mac2port[10]  {8, 8, 0, 1, 2, 3, 4, 5, 6, 7};
#endif
