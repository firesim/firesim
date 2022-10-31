External Tutorial Setup
===================================

This section of the documentation is for external attendees of a
in-person FireSim and Chipyard tutorial.
Please follow along with the following steps to get setup if you already have an AWS EC2 account.

.. Note:: These steps should take around 2hrs if you already have an AWS EC2 account.

1. Start following the FireSim documentation from :ref:`initial-setup` but ending at :ref:`setting-up-firesim-repo` (make sure to **NOT** clone the FireSim repository)

2. Run the following commands:

::

    #!/bin/bash

    FIRESIM_MACHINE_LAUNCH_GH_URL="https://raw.githubusercontent.com/firesim/firesim/final-tutorial-2022-isca/scripts/machine-launch-script.sh"

    curl -fsSLo machine-launch-script.sh "$FIRESIM_MACHINE_LAUNCH_GH_URL"
    chmod +x machine-launch-script.sh
    ./machine-launch-script.sh

    source ~/.bashrc

    export MAKEFLAGS=-j16

    sudo yum install -y nano

    mkdir -p ~/.vim/{ftdetect,indent,syntax} && for d in ftdetect indent syntax ; do wget -O ~/.vim/$d/scala.vim https://raw.githubusercontent.com/derekwyatt/vim-scala/master/$d/scala.vim; done

    echo "colorscheme ron" >> /home/centos/.vimrc


    cd ~/

    (
    git clone https://github.com/ucb-bar/chipyard -b final-tutorial-2022-isca-morning chipyard-morning
    cd chipyard-morning
    ./scripts/init-submodules-no-riscv-tools.sh --skip-validate

    ./scripts/build-toolchains.sh ec2fast
    source env.sh

    ./scripts/firesim-setup.sh --fast
    cd sims/firesim
    source sourceme-f1-manager.sh

    cd ~/chipyard-morning/sims/verilator/
    make
    make clean

    cd ~/chipyard-morning
    chmod +x scripts/repo-clean.sh
    ./scripts/repo-clean.sh
    git checkout scripts/repo-clean.sh

    )

    cd ~/

    (
    git clone https://github.com/ucb-bar/chipyard -b final-tutorial-2022-isca chipyard-afternoon
    cd chipyard-afternoon
    ./scripts/init-submodules-no-riscv-tools.sh --skip-validate

    ./scripts/build-toolchains.sh ec2fast
    source env.sh

    ./scripts/firesim-setup.sh --fast
    cd sims/firesim
    source sourceme-f1-manager.sh
    cd sim
    unset MAKEFLAGS
    make f1
    export MAKEFLAGS=-j16

    cd ../sw/firesim-software
    ./init-submodules.sh
    marshal -v build br-base.json

    cd ~/chipyard-afternoon/generators/sha3/software/
    git submodule update --init esp-isa-sim
    git submodule update --init linux
    ./build-spike.sh
    ./build.sh

    cd ~/chipyard-afternoon/generators/sha3/software/
    marshal -v build marshal-configs/sha3-linux-jtr-test.yaml
    marshal -v build marshal-configs/sha3-linux-jtr-crack.yaml
    marshal -v install marshal-configs/sha3*.yaml

    cd ~/chipyard-afternoon/sims/firesim/sim/
    unset MAKEFLAGS
    make f1 DESIGN=FireSim TARGET_CONFIG=WithNIC_DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.QuadRocketConfig PLATFORM_CONFIG=BaseF1Config
    make f1 DESIGN=FireSim TARGET_CONFIG=WithNIC_DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.LargeBoomConfig PLATFORM_CONFIG=BaseF1Config
    make f1 DESIGN=FireSim TARGET_CONFIG=WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.RocketConfig PLATFORM_CONFIG=BaseF1Config
    make f1 DESIGN=FireSim TARGET_CONFIG=WithNIC_DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.Sha3RocketConfig PLATFORM_CONFIG=BaseF1Config
    make f1 DESIGN=FireSim TARGET_CONFIG=DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.Sha3RocketConfig PLATFORM_CONFIG=BaseF1Config
    make f1 DESIGN=FireSim TARGET_CONFIG=DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.Sha3RocketPrintfConfig PLATFORM_CONFIG=WithPrintfSynthesis_BaseF1Config
    export MAKEFLAGS=-j16

    cd ~/chipyard-afternoon
    chmod +x scripts/repo-clean.sh
    ./scripts/repo-clean.sh
    git checkout scripts/repo-clean.sh

    )

3. Next copy the following contents and replace your entire ``~/.bashrc`` file with this:

::

    # .bashrc
    # Source global definitions
    if [ -f /etc/bashrc ]; then
            . /etc/bashrc
    fi
    # Uncomment the following line if you don't like systemctl's auto-paging feature:
    # export SYSTEMD_PAGER=
    # User specific aliases and functions
    cd /home/centos/chipyard-afternoon && source env.sh && cd sims/firesim && source sourceme-f1-manager.sh && cd /home/centos/
    export FDIR=/home/centos/chipyard-afternoon/sims/firesim/
    export CDIR=/home/centos/chipyard-afternoon/

4. All done! Now continue with the in-person tutorial.
