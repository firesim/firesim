""" Install firesim-software stuff into firesim """

import json
from pathlib import Path
from .wlutil import *

# firesim workloads directory
fsWork = (Path(root_dir) / "../../deploy/workloads").resolve()

readmeTxt="""This workload was generated using firesim-software. See the following config
and workload directory for details:
"""

def installWorkload(cfgName, cfgs):
    targetCfg = cfgs[cfgName]
    if 'jobs' in targetCfg:
        raise NotImplementedError("Jobs not currently supported by the install command")
    if targetCfg['initramfs'] == True:
        raise NotImplementedError("Initramfs-based builds not currently supported by the install command")

    fsTargetDir = fsWork / targetCfg['name']
    if not fsTargetDir.exists():
        fsTargetDir.mkdir()

    # Firesim config
    fsCfg = {
            "benchmark_name" : targetCfg['name'],
            "common_bootbinary" : os.path.relpath(targetCfg['bin'], start=str(fsTargetDir)),
            "common_simulation_outputs" : ["uartlog"]
            }
    if 'img' in targetCfg:
        fsCfg["common_rootfs"] = os.path.relpath(targetCfg['img'], start=str(fsTargetDir))
    else:
        fsCfg["common_rootfs"] = "dummy.rootfs" 
        if not (fsTargetDir / 'dummy.rootfs').exists():
            (fsTargetDir / 'dummy.rootfs').symlink_to(Path(wlutil_dir) / 'dummy.rootfs')

    if 'outputs' in targetCfg:
        fsCfg["common_outputs"] = targetCfg['outputs']
    if 'post_run_hook' in targetCfg:
        fsCfg["post_run_hook"] = os.path.relpath(targetCfg['post_run_hook'], start=str(fsTargetDir))

    with open(str(fsTargetDir / "README"), 'w') as readme:
        readme.write(readmeTxt)
        readme.write(os.path.relpath(targetCfg['cfg-file'], start=str(fsTargetDir)) + "\n")
        readme.write(os.path.relpath(targetCfg['workdir'], start=str(fsTargetDir)) + "\n")

    with open(str(fsWork / (targetCfg['name'] + '.json')), 'w') as fsCfgFile:
        json.dump(fsCfg, fsCfgFile, indent='\t')
