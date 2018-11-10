FireSim Asked Questions
=============================

I just bumped the FireSim repository to a newer commit and simulations aren't running. What is going on?
--------------------------------------------------------------------------------------------------------

Anytime there is an AGFI bump, FireSim simulations will break/hang due to outdated AFGI. To get the new default AGFI's you must run the manager initialization again by doing the following:

::
    
    cd firesim/
    source sourceme-f1-manager.sh
    firesim managerinit
