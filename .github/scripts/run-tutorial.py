#!/usr/bin/env python3

from fabric.api import prefix, settings, run, execute # type: ignore

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_tutorial():
    run("git clone https://github.com/ucb-bar/chipyard -b hpca-2023-morning chipyard-afternoon")
    with prefix("cd chipyard-afternoon"):
        run("./build-setup.sh riscv-tools -f")
        with prefix("source env.sh"):
            run("./scripts/firesim-setup.sh")

            with prefix("cd sims/firesim"):
                with prefix("source sourceme-f1-manager.sh && unset MAKEFLAGS && cd sim"):
                    run("make f1")

            with prefix("cd sw/firesim-software"):
                run("./init-submodules.sh")
                run("marshal -v build br-base.json")

            with prefix("cd generators/sha3/software"):
                run("git submodule update --init esp-isa-sim")
                run("git submodule update --init linux")
                run("./build-spike.sh")
                run("./build.sh")

                run("marshal -v build marshal-configs/sha3-linux-jtr-test.yaml")
                run("marshal -v build marshal-configs/sha3-linux-jtr-crack.yaml")
                run("marshal -v install marshal-configs/sha3*.yaml")

            with prefix("cd sims/firesim"):
                run("source sourceme-f1-manager.sh")
                run("unset MAKEFLAGS")
                with prefix("cd sim"):
                    run("make f1 DESIGN=FireSim TARGET_CONFIG=WithNIC_DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.QuadRocketConfig PLATFORM_CONFIG=BaseF1Config")
                    run("make f1 DESIGN=FireSim TARGET_CONFIG=WithNIC_DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.LargeBoomConfig PLATFORM_CONFIG=BaseF1Config")
                    run("make f1 DESIGN=FireSim TARGET_CONFIG=WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.RocketConfig PLATFORM_CONFIG=BaseF1Config")
                    run("make f1 DESIGN=FireSim TARGET_CONFIG=WithNIC_DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.Sha3RocketConfig PLATFORM_CONFIG=BaseF1Config")
                    run("make f1 DESIGN=FireSim TARGET_CONFIG=DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.Sha3RocketConfig PLATFORM_CONFIG=BaseF1Config")
                    run("make f1 DESIGN=FireSim TARGET_CONFIG=DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.Sha3RocketPrintfConfig PLATFORM_CONFIG=WithPrintfSynthesis_BaseF1Config")

            run("chmod +x scripts/repo-clean.sh")
            run("./scripts/repo-clean.sh")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_tutorial, hosts=["localhost"])
