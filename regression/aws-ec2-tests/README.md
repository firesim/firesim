Assumes the following:
- Are running on an AWS instance (needed to access private IPs + username centos)
- Have a working AWS setup (ran aws configure)
- Have a specific hash to test (hash must be accessible from the mainline firesim repo)

1. Launch manager using script (should log the IP address in a file to use)
2. Run a specific regression (up to the user to ensure that it ends properly)
3. Terminate manager instance
