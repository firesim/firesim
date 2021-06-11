import logging
import pathlib
import wlutil
import shutil

def install(targetCfg, opts):
    log = logging.getLogger()

    if targetCfg['nodisk'] == False:
        raise NotImplementedError("nodisk builds are the only workload type supported by the install command")

    nodiskPath = str(targetCfg['bin']) + '-nodisk'
    outputPath = nodiskPath + '-flat'

    if targetCfg['firmware']['use-bbl'] == False:
        wlutil.run(['riscv64-unknown-elf-objcopy', '-S', '-O', 'binary', '--change-addresses', '-0x80000000',
            nodiskPath, outputPath])
    else:
        print("Copy BBL's flattened binary to " + outputPath)
        shutil.copy(str(targetCfg['firmware']['bbl-src']) + '/build/bbl.bin', outputPath)

    log.info("Workload flattened and \"installed\" to " + outputPath)
