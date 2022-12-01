import json
import logging
import pathlib
import os
import wlutil

moduleDir = pathlib.Path(__file__).resolve().parent

readmeTxt = """This workload was generated using FireMarshal. See the following config
and workload directory for details:
"""


# Create a relative path from base to target. Pathlib.path.relative_to doesn't work.
# Assumes that wlDir and target are absolute paths (either string or pathlib.Path)
# Returns a string
def fullRel(base, target):
    return os.path.relpath(str(target), start=str(base))


def install(targetCfg, opts):
    log = logging.getLogger()

    fsDir = opts['firesim-dir']
    if fsDir is None:
        raise wlutil.ConfigurationError("No firesim-dir option is set. Please configure the location of firesim in your config.yaml.")

    fsWork = opts['firesim-dir'] / "deploy/workloads"
    if not fsWork.exists():
        raise wlutil.ConfigurationError("Configured firesim-dir (" + str(fsDir) + ") does not appear to be a valid firesim installation")

    if targetCfg['nodisk']:
        raise NotImplementedError("nodisk builds not currently supported by the install command")

    fsTargetDir = fsWork / targetCfg['name']
    if not fsTargetDir.exists():
        fsTargetDir.mkdir()

    # Path to dummy rootfs to use if no image specified (firesim requires a
    # rootfs, even if it's not used)
    dummyPath = fullRel(fsTargetDir, moduleDir / 'dummy.rootfs')

    # Firesim config
    fsCfg = {
            "benchmark_name": targetCfg['name'],
            "common_simulation_outputs": ["uartlog"]
            }

    if 'post_run_hook' in targetCfg:
        fsCfg["post_run_hook"] = fullRel(fsTargetDir, targetCfg['post_run_hook'])

    if 'jobs' in targetCfg:
        # Multi-node run
        wls = [None]*len(targetCfg['jobs'])
        for slot, jCfg in enumerate(targetCfg['jobs'].values()):
            wls[slot] = {
                    'name': jCfg['name'],
                    'bootbinary': fullRel(fsTargetDir, jCfg['bin'])
                    }
            if 'img' in jCfg:
                wls[slot]["rootfs"] = fullRel(fsTargetDir, jCfg['img'])
            else:
                wls[slot]["rootfs"] = dummyPath

            if 'outputs' in jCfg:
                wls[slot]["outputs"] = [f.as_posix() for f in jCfg['outputs']]
        fsCfg['workloads'] = wls
    else:
        # Single-node run
        fsCfg["common_bootbinary"] = fullRel(fsTargetDir, targetCfg['bin'])

        if 'img' in targetCfg:
            fsCfg["common_rootfs"] = fullRel(fsTargetDir, targetCfg['img'])
        else:
            fsCfg["common_rootfs"] = dummyPath

        if 'outputs' in targetCfg:
            fsCfg["common_outputs"] = [f.as_posix() for f in targetCfg['outputs']]

    with open(str(fsTargetDir / "README"), 'w') as readme:
        readme.write(readmeTxt)
        readme.write(os.path.relpath(targetCfg['cfg-file'], start=str(fsTargetDir)) + "\n")
        readme.write(os.path.relpath(targetCfg['workdir'], start=str(fsTargetDir)) + "\n")

    fsConfigPath = fsWork / (targetCfg['name'] + '.json')
    with open(str(fsConfigPath), 'w') as fsCfgFile:
        json.dump(fsCfg, fsCfgFile, indent='\t')

    log.info("Workload installed to FireSim at " + str(fsConfigPath))
