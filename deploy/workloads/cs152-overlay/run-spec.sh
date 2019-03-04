#! /bin/bash
echo "Running the SPEC intspeed subsuite."
cd /spec17-intspeed/ && ./intspeed.sh 605.mcf_s --counters
cd /spec17-intspeed/ && ./intspeed.sh 620.omnetpp_s --counters
cd /spec17-intspeed/ && ./intspeed.sh 631.deepsjeng_s --counters
