#!/usr/bin/env python3

from fabric.api import *

from common import manager_fsim_dir, manager_ci_dir, manager_fsim_pem, set_fabric_firesim_pem

import sys

def run_build_recipes_yaml_api_tests():
    """ Test config_{build, build_recipes}.yaml APIs """

    def commands_to_run(commands, opts):
        """ Run a list of commands with the specified opts """
        for command in commands:
            with prefix('cd {} && source sourceme-f1-manager.sh'.format(manager_fsim_dir)):
                rc = 0
                with settings(warn_only=True):
                    rc = run("{} {}".format(command, opts)).return_code
                if rc == 0:
                    print("{} passed unexpectedly.".format(command))

                    # exit since passing is not wanted
                    sys.exit(1)

    def run_test(name):
        # test files should exist on the manager already
        test_dir = "{}/yaml-tests/failing-buildafi-files/{}".format(manager_ci_dir, name)

        commands_to_run(
                ["firesim buildafi"],
                "-b {}/sample_config_build.yaml -r {}/sample_config_build_recipes.yaml -s {}/sample_config_build_farm.yaml".format(test_dir, test_dir, test_dir))

    run_test("invalid-build-section")
    run_test("invalid-aws-ec2-inst-type")
    run_test("invalid-buildfarm-type")
    run_test("invalid-aws-ec2-no-args")
    run_test("invalid-unmanaged-no-args")
    run_test("invalid-unmanaged-no-hosts")

    # test invalid config_build.yaml
    commands_to_run(["firesim buildafi"], "-b ~/GHOST_FILE")

    # test invalid config_build_recipes.yaml
    commands_to_run(["firesim buildafi"], "-r ~/GHOST_FILE")

    # test invalid config_build_farm.yaml
    commands_to_run(["firesim buildafi"], "-s ~/GHOST_FILE")

def run_runtime_hwdb_yaml_api_tests():
    """ Test config_{runtime, hwdb}.yaml APIs """

    def commands_to_run(commands, opts):
        """ Run a list of commands with the specified opts """
        for command in commands:
            with prefix('cd {} && source sourceme-f1-manager.sh'.format(manager_fsim_dir)):
                rc = 0
                with settings(warn_only=True):
                    rc = run("{} {}".format(command, opts)).return_code
                if rc == 0:
                    print("{} passed unexpectedly.".format(command))

                    # test passed so make sure to terminate runfarm
                    run("firesim terminaterunfarm -q {}".format(opts))

                    # exit since passing is not wanted
                    sys.exit(1)

    def run_test(name):
        # test files should exist on the manager already
        test_dir = "{}/yaml-tests/failing-runtime-files/{}".format(manager_ci_dir, name)

        commands_to_run(
                ["firesim launchrunfarm", "firesim infrasetup", "firesim runworkload", "firesim terminaterunfarm -q"],
                "-c {}/sample_config_runtime.yaml -a {}/sample_config_hwdb.yaml".format(test_dir, test_dir))

    run_test("hwdb-invalid-afi")
    run_test("runtime-invalid-hwconfig")
    run_test("runtime-invalid-topology")
    run_test("runtime-invalid-workloadname")

    # test invalid config_runtime.yaml
    commands_to_run(["firesim launchrunfarm", "firesim infrasetup", "firesim runworkload", "firesim terminaterunfarm -q"], "-c ~/GHOST_FILE")

    # test invalid config_hwdb.yaml
    commands_to_run(["firesim launchrunfarm", "firesim infrasetup", "firesim runworkload", "firesim terminaterunfarm -q"], "-a ~/GHOST_FILE")

def run_yaml_api_tests():
    """ Test manager .yaml file APIs """

    run_build_recipes_yaml_api_tests()
    run_runtime_hwdb_yaml_api_tests()

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_yaml_api_tests, hosts=["localhost"])
