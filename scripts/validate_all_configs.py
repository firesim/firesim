#!/usr/bin/env python3

import os
import yamale
import sys
import yaml

fsim_dir = os.path.dirname(os.path.realpath(__file__)) + "/.."
sys.path.append(f"{fsim_dir}/deploy")

from util.configvalidation import validate

rel_paths = {
    "sample-backup-configs/sample_config_build_recipes.yaml",
    "sample-backup-configs/sample_config_build.yaml",
    "sample-backup-configs/sample_config_hwdb.yaml",
    "sample-backup-configs/sample_config_runtime.yaml",
    "build-farm-recipes/aws_ec2.yaml",
    "build-farm-recipes/externally_provisioned.yaml",
    "run-farm-recipes/aws_ec2.yaml",
    "run-farm-recipes/externally_provisioned.yaml",
    "bit-builder-recipes/f1.yaml",
    "bit-builder-recipes/vitis.yaml",
}

# <REL. PATH> : (<ABS. PATH>, <SCHEMA>)
paths_dict = dict([(p, (f"{fsim_dir}/deploy/{p}", f"{fsim_dir}/deploy/schemas/{p}")) for p in rel_paths])

# validate all configs
# TODO: validate override section
for k, (src_yaml, schema_yaml) in paths_dict.items():
    if not validate(src_yaml, schema_yaml):
        raise Exception

print("All validations successful")
