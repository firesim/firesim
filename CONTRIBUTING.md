Contributing to FireSim
=============================

### Branch management:

1) github:com/firesim/firesim: master = stable release. All merges to master must go through PR.
2) Other repos in FireSim github org: master should be the version submoduled in firesim/firesim master.
3) Forks in FireSim github org (e.g. riscv-tools): master reflects newest upstream that we’ve bumped to, firesim branch that reflects what’s submoduled in firesim/firesim, firesim is the default branch of the fork
4) Other deps (e.g. midas, testchipip): firesim branch that reflects what’s submoduled in firesim, should follow the same PR discipline as merging into firesim/firesim master

For 2, 3, 4, the PR to FireSim implicitly is PRing to the appropriate branch in the submodule.
