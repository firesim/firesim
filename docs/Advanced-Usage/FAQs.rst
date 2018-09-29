FireSim Asked Questions
=============================

I just upgraded to a recent commit but my simulation is freezing in the target. What is going on?
----------------------------------------------------------------------------------------------------

This could be for a variety of reasons. However, one reason might be that after an AGFI bump 
your simulation will break/hang. You can check for this by looking at the commit log. To fix
this particular problem, you should rerun the following to get new default AGFIs:

::
    
    cd firesim/
    source sourceme-f1-manager.sh
    firesim managerinit

A general safe practice is that whenever you move to a newer commit, you should run the following command as
a first step to make sure that the AGFIs are updated.
