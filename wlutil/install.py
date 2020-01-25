""" Install firesim-software stuff into firesim """

import json
from pathlib import Path
from .wlutil import *

readmeTxt="""This workload was generated using firesim-software. See the following config
and workload directory for details:
"""

# Create a relative path from base to target.
# Assumes that wlDir and target are absolute paths (either string or pathlib.Path)
# Returns a string
def fullRel(base, target):
    return os.path.relpath(str(target), start=str(base))

def installWorkload(cfgName, cfgs):
    fsWork = ((getOpt('root-dir') / "../../deploy/workloads").resolve())
    if fsWork == None:
        raise RuntimeError("The install command is not supported when firesim-software is checked out standalone (i.e. not a submodule of firesim).")

    targetCfg = cfgs[cfgName]
    # if 'jobs' in targetCfg:
    #     raise NotImplementedError("Jobs not currently supported by the install command")
    if targetCfg['nodisk'] == True:
        raise NotImplementedError("Initramfs-based builds not currently supported by the install command")

    fsTargetDir = fsWork / targetCfg['name']
    if not fsTargetDir.exists():
        fsTargetDir.mkdir()

    # Path to dummy rootfs to use if no image specified (firesim requires a
    # rootfs, even if it's not used)
    dummyPath = fullRel(fsTargetDir, getOpt('wlutil-dir') / 'dummy.rootfs')

    # Firesim config
    fsCfg = {
            "benchmark_name" : targetCfg['name'],
            "common_simulation_outputs" : ["uartlog"]
            }

    if 'post_run_hook' in targetCfg:
        print("post_run_hook: " + targetCfg['post_run_hook'])
        fsCfg["post_run_hook"] = fullRel(fsTargetDir, targetCfg['post_run_hook'])

    if 'jobs' in targetCfg:
        # Multi-node run
        wls = [None]*len(targetCfg['jobs'])
        for slot, jCfg in enumerate(targetCfg['jobs'].values()):
            wls[slot] = {
                    'name' : jCfg['name'],
                    'bootbinary' : fullRel(fsTargetDir, jCfg['bin'])
                    }
            if 'img' in jCfg:
                wls[slot]["rootfs"] = fullRel(fsTargetDir, jCfg['img'])
            else:
                wls[slot]["rootfs"] = dummyPath

            if 'outputs' in jCfg:
                wls[slot]["outputs"] = [ f.as_posix() for f in jCfg['outputs'] ]
        fsCfg['workloads'] = wls
    else:
        # Single-node run
        fsCfg["common_bootbinary"] = fullRel(fsTargetDir, targetCfg['bin'])

        if 'img' in targetCfg:
            fsCfg["common_rootfs"] = fullRel(fsTargetDir, targetCfg['img'])
        else:
            fsCfg["common_rootfs"] = dummyPath

        if 'outputs' in targetCfg:
            fsCfg["common_outputs"] = [ f.as_posix() for f in targetCfg['outputs'] ]

    with open(str(fsTargetDir / "README"), 'w') as readme:
        readme.write(readmeTxt)
        readme.write(os.path.relpath(targetCfg['cfg-file'], start=str(fsTargetDir)) + "\n")
        readme.write(os.path.relpath(targetCfg['workdir'], start=str(fsTargetDir)) + "\n")

    with open(str(fsWork / (targetCfg['name'] + '.json')), 'w') as fsCfgFile:
        json.dump(fsCfg, fsCfgFile, indent='\t')
