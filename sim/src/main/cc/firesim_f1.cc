#ifndef RTLSIM
#include "simif_f1.h"
#else
#include "simif_emul.h"
#endif
#include "firesim_top.h"
#include "fesvr/firesim_tsi.h"
#include <iostream>

#ifdef RTLSIM
// top for RTL sim
class firesim_f1_t:
    public simif_emul_t, public firesim_top_t     
{
    public:
        firesim_f1_t(int argc, char** argv, fesvr_proxy_t* fesvr):
            firesim_top_t(argc, argv, fesvr) { }
};
#else
// top for FPGA simulation on F1
class firesim_f1_t:
    public simif_f1_t, public firesim_top_t
{
    public:
        firesim_f1_t(int argc, char** argv, std::vector<fesvr_proxy_t*> fesvr):
            firesim_top_t(argc, argv, fesvr), simif_f1_t(argc, argv) { }
};
#endif

#if defined(SIMULATION_XSIM) || defined(RTLSIM)
// use a small step size for rtl sim
#define DESIRED_STEPSIZE (128)
#else
#define DESIRED_STEPSIZE (2004765L)
#endif

int main(int argc, char** argv) {
  
    //parse different target arguments for different nodes
    std::vector<std::string> args(argv + 1, argv + argc);
    std::vector< std::vector<std::string> > args_vec(4, std::vector<std::string>());
    std::vector< char** > argv_vec(4);
    std::vector< int > argc_vec(4,0);
    char subslotid_str[2] = {NULL};
    for (auto &arg: args) {
       if (arg.find("+prog") == 0)
       {
          subslotid_str[0] = arg.at(5);
          int subnode_id = atoi(subslotid_str);
          std::string clean_target_args = arg.substr(7);

          std::istringstream ss(clean_target_args);
          std::string token;
          while(std::getline(ss, token, ' ')) {
            args_vec[subnode_id].push_back(token);
            argc_vec[subnode_id] = argc_vec[subnode_id] + 1;
  	  }
       }
       else
       {
          for (int i=0; i<4; i++)
          {
            args_vec[i].push_back(arg);
            argc_vec[i] = argc_vec[i] + 1;
          }
       }
    }

    for (int j=0; j<4; j++)
    {
      argv_vec[j] = new char*[args_vec[j].size()];
      for(size_t i = 0; i < args_vec[j].size(); ++i)
      {
          (argv_vec[j])[i] = new char[(args_vec[j])[i].size() + 1];
          std::strcpy((argv_vec[j])[i], (args_vec[j])[i].c_str());
      }
    }

    //debug for command line arguments
    for (int j=0; j<4; j++)
    {
       printf("command line for program %d. argc=%d:\n", j, argc_vec[j]);
       for(int i = 0; i < argc_vec[j]; i++)  { printf("%s", (argv_vec[j])[i]);  }
       printf("\n");
    }


    //actual firesim main
    std::vector<fesvr_proxy_t*> tsi_vec(4);
    for (int i=0; i<4; i++)
    {
      tsi_vec[i] = new firesim_tsi_t(std::vector<std::string>(argv_vec[i], argv_vec[i] + argc_vec[i]));
    }
    firesim_f1_t firesim(argc, argv, tsi_vec);
    firesim.init(argc, argv);

    firesim.run(DESIRED_STEPSIZE);
    for (int i=0; i<4; i++)
    {
      delete tsi_vec[i];
    }
    return firesim.finish();
}




