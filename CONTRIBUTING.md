Contributing to FireSim
=============================


### Branch management:

#### Write-Protected Branches (Require PR approval)
1) master = stable release. Documentation updates and release-exposed bug fixes can be opened against this branch.
2) dev = development branch. Substantial code changes and feature additions must be based against this branch.

#### External Dependencies:
1) Other repos in FireSim github org: master should be the version submoduled in firesim/firesim master. Likewise, firesim/firesim dev should point at dev branches in submodules.
2) Forks in FireSim github org: master reflects newest upstream that we’ve bumped to, firesim branch that reflects what’s submoduled in firesim/firesim, firesim is the default branch of the fork
3) ucb-bar hosted dependencies (e.g. midas): firesim branch that reflects what’s submoduled in firesim, should follow the same PR discipline as merging into firesim/firesim master

PRs to write protected branches (master/dev) that bump submodule pointers must open PRs to the matching branch in the submodule's repository. 

#### Chipyard <-> FireSim Submodule Dependency

Chipyard subsumes FireChip as a more complete SoC development environment. In order to support a use case where either Chipyard or FireSim is clone as the top-level repository, we've introduced a circular submodule depedency between the two repositories. 

- Firesim submodules Chipyard at `target-design/chipyard`
- Chipyard submodules Firesim at `sims/firesim`

*This only applies to circumstances in which you are making changes to both Chipyard and Firesim.*
Joint modifications to Chipyard and FireSim sources will require additional commit in at least one of the two repositories.   Our recommend procedure is as follows. Commits are labeled <COMMIT_NAME>(<SUBMODULE_COMMIT_NAME>). 

```
Firesim:dev  ->  A(I) -> ... ->        ?(?)              E(L) Fast-forward (5)
                    \                     \             / 
Firesim:feature     B(J) -> ... -> C(K~1) -> D(?) -> E(L)
                                     (1)     (2)     (4)


Chipyard:dev -> I(A) -> ...       ->   ?(?)     L(D) Fast-forward (5)
                    \                      \    /
Chipyard:feature     J(A) -> ... -> K(C) -> L(D)
                                     (1)     (3)

                             
(1) Create a feature branch and develop it. CI must pass in Chipyard. 
(2) Merge firesim:dev into firesim:feature. At this point the CY submodule pointer cannot be resolved.
(3) Merge chipyard:dev into chipyard:feature. Point the firesim submodule pointer at D
(4) Bump chipyard in firesim:feature to point at E.
(5) Merge (fast-forward) feature branches into dev branches
```

Now, checking out commit E when using FireSim as your top-level repository, or checking out commit L if using Chipyard as your top-level repository should provide a source-identical user experience, with submodules pointing at commits on dev.

### AGFI Generation:

The head of dev and master branches must always have pre-generated AGFIs derived from the same sources provided by FireSim and Chipyard. Any commit that could change that would produce different RTL than was used to generate the AGFI, should rebuild, and publish freshly generated AGFIs. We do this to make it possible to bisect on commits that regenerated the AGFIs to find the source of a simulation bug, without needing to regenerate AGFIs as we bisect. 

External contributors: if you do not have the resources to regenerate all pre-built AGFIs, feel free to reach out to firesim developers.

