Getting Started with On Premises FPGA Frequently Asked Questions
============================================================================================

To streamline the set-up process, we provide fix suggestions to the following errors that might appear during the process for reference.

**SSH Setup**
  ``build bitstream`` process stuck at ::
  
  [localhost] local: pwd
  [localhost] run: ...
  
  
  The manager machine should be able to use ssh protocal to connect to build_farm and run_farm machines without providing password or passphrase duirng the connection process. 
  
  Verify this by using ``ssh`` command from the manager machine. If a password or passphrase prompt appears, ssh-agent can be used to store and automatically provide login credentials. ::
  
  ssh-agent -s > AGENT_VARS
  source AGENT_VARS
  ssh-add ~/.ssh/firesim.pem

  Please also note that ssh-agent might need to be set-up each time a new terminal is started. 

**Build Bitstream**
  ``protocol version mismatch -- is your shell clean?``
  
  This error is probably due to shell login scripts writing to standard out.
  
  Edit ``~/.bashrc`` and/or other login scripts to make sure standard out writing commands such as ``echo`` are either commented out or only executed when the shell is in interactive mode(directly receving commands typed by a user).
