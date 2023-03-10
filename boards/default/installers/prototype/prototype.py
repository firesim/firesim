import logging
import wlutil


def install(targetCfg, opts):
    log = logging.getLogger()

    if targetCfg['nodisk'] is False:
        raise NotImplementedError("nodisk builds are the only workload type supported by the install command")

    nodiskPath = str(targetCfg['bin']) + '-nodisk'
    outputPath = nodiskPath + '-flat'

    wlutil.run(['riscv64-unknown-elf-objcopy', '-S', '-O', 'binary', '--change-addresses', '-0x80000000',
                nodiskPath, outputPath])
    log.info("Workload flattened and \"installed\" to " + outputPath)
