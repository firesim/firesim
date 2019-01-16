FireSim Asked Questions
=============================

I just bumped the FireSim repository to a newer commit and simulations aren't running. What is going on?
--------------------------------------------------------------------------------------------------------

Anytime there is an AGFI bump, FireSim simulations will break/hang due to outdated AFGI.
To get the new default AGFI's you must run the manager initialization again by doing the following:

::
    
    cd firesim
    source sourceme-f1-manager.sh
    firesim managerinit

Is there a good way to keep track of what AGFI corresponds to what FireSim commit?
----------------------------------------------------------------------------------

When building an AGFI during ``firesim buildafi``, FireSim keeps track of what FireSim repository commit was used to build the AGFI.
To view a list of AGFI's that you have built and what you have access to, you can run the following command:

::

    cd firesim
    source sourceme-f1-manager.sh
    aws ec2 describe-fpga-images --fpga-image-ids # List all AGFI images

You can also view a specific AGFI image by giving the AGFI ID (found in ``deploy/config_hwdb.ini``) through the following command:

::
    
    cd firesim
    source sourceme-f1-manager.sh
    aws ec2 describe-fpga-images --filter Name=fpga-image-global-id,Values=agfi-<Your ID Here> # List particular AGFI image

After querying an AGFI, you can find the commit hash of the FireSim repository used to build the AGFI within the "Description"
field. 

For more information, you can reference the AWS documentation at https://docs.aws.amazon.com/cli/latest/reference/ec2/describe-fpga-images.html.
