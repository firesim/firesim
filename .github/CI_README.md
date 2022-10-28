FireSim Continuous Integration
==============================

Helpful Links:
* Workflow GUI - https://github.com/firesim/firesim/actions
* Chipyard Explanation of Github Actions (GH-A) - https://github.com/ucb-bar/chipyard/blob/main/.github/CI_README.md

Github Actions (GH-A) Description
---------------------------------

Much of the following CI infrastructure is based on the Chipyard CI.
For a basic explanation of how the GH-A CI works, see https://github.com/ucb-bar/chipyard/blob/main/.github/CI_README.md.
However, there are a couple of notable differences/comments as follows:

* In order to provide a fresh environment to test changes, the CI dynamically spawns a AWS instance and sets up GH-A
to use this instance as a GH-A self-hosted runner (see https://docs.github.com/en/actions/hosting-your-own-runners/about-self-hosted-runners).
    * All scripts that run on the manager instance use Fabric with `localhost` and each GH-A step must include `runs-on: ${{ github.run_id }}` KV pair to indicate that you want to run directly on a runner that is on the manager instance.
    * Currently only 4 runners are spawned per workflow (reused across multiple workflow runs - i.e. clicking rerun). Every commit gets its own manager instance and 4 runners that run on the manager instance.
    * When the CI terminates/stops an instance, the CI automatically deregisters the runners from GH-A (any runner API calls require a personal token with the `repo` scope).
* The CI structure is as follows:
    1. Launch the manager instance + setup the N self-hosted runners.
    2. Run the original initialization (add the firesim.pem, run the build setup, etc).
    3. Continue with the rest of the tests using the GH-A runners.


Running FPGA-related Tasks
--------------------------

CI now includes the capability to run FPGA-simulations on specific PRs. This requires that you tag your PR on creation with the tag `ci:fpga-deploy`. Adding the tag after the PR is created will not run the FPGA jobs without a resynchronization event (e.g., closing + reopening the PR, adding a new commit, or rebasing the branch). 

Debugging Failures
------------------

When a failure occurs on the manager instance the CI will stop or terminate the instance (terminate if your instance is using the spot market).
Currently, the only way to access any running instance that is created from the CI is to do the following:

1. In the PR, apply the `ci:debug` flag to prevent the CI instance from being terminated
2. Request the CI PEM file needed to SSH into the CI instances (ask the FireSim developers)
3. Obtain the public IP address from the "Launch AWS instance used for the FireSim manager (instance info found here)" (which runs the `launch-manager-instance.py` script) step in the `setup-self-hosted-manager` job in the CI.
4. SSH into the instance and do any testing required.

If the instance is stopped, then you must request a AWS IAM user account from the FireSim developers to access the EC2 console and restart the instance.

GitHub Secrets
--------------
* **AWS_ACCESS_KEY_ID**: Passed to `aws configure` on CI containers + manager instances
* **AWS_DEFAULT_REGION**: Passed to `aws configure` on CI containers + manager instances
* **AWS_SECRET_ACCESS_KEY**: Passed to `aws configure` on CI containers + manager instances
* **AZURE_CLIENT_ID**: Used to manage Azure resources
* **AZURE_CLIENT_SECRET**: Used to manage Azure resources
* **AZURE_TENANT_ID**: Used to manage Azure resources
* **AZURE_SUBSCRIPTION_ID**: Subscription ID of Azure account to run CI on
* **AZURE_DEFAULT_REGION**: Used to setup Azure region
* **AZURE_RESOURCE_GROUP**: Resource group Azure is running in
* **AZURE_CI_SUBNET_ID**: Subnet used by Azure VMs running CI
* **AZURE_CI_NSG_ID**: Network Security Group used by Azure VMs running CI
* **FIRESIM_PEM**: Used by the manager on CI manager instances and VMs
* **FIRESIM_PEM_PUBLIC**: Public key of the above secret, used to setup the key in Azure
* **FIRESIM_REPO_DEP_KEY**: Used to push scala doc to GH pages
* **GH_A_PERSONAL_ACCESS_TOKEN**: Used to dynamically register and deregister GitHub Actions runners. See `https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token`, and enable the `workflow` (Update GitHub Action workflows) setting.
