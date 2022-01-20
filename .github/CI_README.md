FireSim Continuous Integration
==============================

Helpful Links:
* Workflow GUI - https://github.com/firesim/firesim/actions
* Chipyard Explanation of Github Actions (GH-A) - https://github.com/ucb-bar/chipyard/blob/dev/.github/CI_README.md

Github Actions (GH-A) Description
---------------------------------

Much of the following CI infrastructure is based on the Chipyard CI.
For a basic explanation of how the GH-A CI works, see https://github.com/ucb-bar/chipyard/blob/dev/.github/CI_README.md.
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

CI now includes the capability to run FPGA-simulations on specific PRs.
However, by default, this requires approval from the `firesim-fpga-approval` team (called a "deployment").
You can gain approval to run FPGA-simulations in two ways.

1. Each member in the `firesim-fpga-approval` team will receive an email asking for approval on a specific PR. From that email, they can approve the request and run the FPGA-simulation tests.
2. From the workflow run GUI (go to https://github.com/firesim/firesim/actions and click a specific workflow run) a `firesim-fpga-approval` team member can approve the deployment (note this button only shows up once the job that needs approval is reached).

Debugging Failures
------------------

When a failure occurs on the manager instance the CI will stop or terminate the instance (terminate if your instance is using the spot market).
Currently, the only way to access any running instance that is created from the CI is to do the following:

1. Request the CI PEM file needed to SSH into the CI instances (ask the FireSim developers)
2. Obtain the public IP address from the "Launch AWS instance used for the FireSim manager (instance info found here)" (which runs the `launch-manager-instance.py` script) step in the `setup-self-hosted-manager` job in the CI.
3. SSH into the instance and do any testing required.

If the instance is stopped, then you must request a AWS IAM user account from the FireSim developers to access the EC2 console and restart the instance.
