The SLASH dependency is pinned in `slash.json` to the public
[Xilinx/SLASH source](https://github.com/Xilinx/SLASH/commit/26f9c55ca1ce7d766715d51eb4dd5f3b50b8f3fc).
The patches retain SLASH's MIT license. They provide optional pre-optimization
clock constraints and routed debug-probe export hooks, preload libcap with
libudev for Vivado's DDR calibration compiler on newer Linux hosts, and make
buddy allocation exhaustion throw `std::bad_alloc` instead of returning a
null slice. They contain no DRC severity changes or floorplan extensions.

Apply them to a clean checkout at the pinned commit:

```sh
python3 platforms/xilinx_alveo_v80_slash/prepare-slash.py /path/to/SLASH
```

The preparation command checks the baseline, patch hashes and resulting source
hashes. Repeating it verifies the same patched files; unrelated changes are
rejected. It does not install drivers, start services, access the card or
download shell IP. Initialize SLASH's own pinned submodules when building its
libraries. Follow SLASH's installation instructions for its system dependencies
and static shell; the shell's licensed inputs and generated checkpoints are
not distributed in this repository.

FireSim uses `vrt::Device(..., false)` with a previously loaded image.
Programming and clock configuration belong to the qualified deployment runner,
which must verify the image and actual runtime clock before starting the driver.
The legacy local SDK's automatic programming cache and clock fallback behavior
are not required by this path.

Building the userspace libraries into an isolated prefix is supported by
SLASH's CMake options `VRT_INCLUDE_VRTD=ON` and `VRTD_INCLUDE_LIBSLASH=ON`.
Point `VRT_HOME` at the matching headers/libraries. This installs userspace artifacts into the selected prefix without activating
a new daemon or kernel module. Record the active host module and service
fingerprints in deployment acceptance.
