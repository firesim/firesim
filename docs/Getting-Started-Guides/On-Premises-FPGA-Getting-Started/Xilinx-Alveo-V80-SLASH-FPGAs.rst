AMD Alveo V80 FPGAs with SLASH
==============================

The ``xilinx_alveo_v80_slash`` platform uses the SLASH DFX shell, with its own runtime,
build recipe and deployment format. Select it explicitly in both the build and run
configurations. See :doc:`Xilinx-Alveo-V80-FPGAs` for the separate AVED platform. The
two shells require different images.

.. important::

    Matching GCD and single-Rocket artifacts have completed physical acceptance at 40
    MHz. Rocket coverage includes three ISA programs and Linux boot, root-shell disk
    read/write and HTIF poweroff. Additional stream, counter, debug and Manager trials
    are recorded in ``platforms/xilinx_alveo_v80_slash/VALIDATION.md``, with matching
    source identities and limits. These results do not qualify other target designs or
    guest networking. No prebuilt deployment or private host configuration is supplied
    by this guide.

Requirements
------------

Start with a FireSim/Chipyard development environment and source the appropriate
``sourceme-manager.sh`` or ``env.sh`` before invoking the Make flow below. Commands run
from the FireSim repository root. The example paths are installation paths to replace
with your own, and each output directory must be new.

Provide the following reviewed dependencies:

- A V80 host configured for SLASH, including compatible kernel module, daemon and
  userspace libraries. Keep the active host versions and their fingerprints with the
  board acceptance record.
- SLASH at commit ``26f9c55ca1ce7d766715d51eb4dd5f3b50b8f3fc``, its pinned submodules,
  and the patches checked by ``platforms/xilinx_alveo_v80_slash/prepare-slash.py``.
  Follow ``platforms/xilinx_alveo_v80_slash/dependencies/README.md`` for preparation. An
  isolated userspace installation does not activate a daemon or module.
- Vivado 2025.1, the compatible full static-shell checkpoint, and a previously accepted
  full-context checkpoint for static-configuration comparison. Licensed shell inputs and
  generated checkpoints are not included here. Establishing the first accepted static
  baseline is a separate prerequisite.
- Reviewed host programming, clock and clock-measurement tools. The deployment runner
  expects adapters described in ``platforms/xilinx_alveo_v80_slash/README.md``; this
  guide does not provide a complete host installation or replacement clock-tool
  implementation.

CPU checks and design generation
--------------------------------

Run the platform CPU suites in the FireSim Manager Python environment:

.. code-block:: bash

    python3 platforms/xilinx_alveo_v80_slash/tests/test_build_tools.py
    python3 platforms/xilinx_alveo_v80_slash/tests/test_deployment.py
    python3 platforms/xilinx_alveo_v80_slash/tests/test_manager.py

These suites predict tool and card responses. They cannot establish PCIe, DDR, timing or
physical reset behavior.

The following GCD example generates RTL, builds its matching native driver and delivers
build inputs. It does not program the card:

.. code-block:: bash

    make -C sim PLATFORM=xilinx_alveo_v80_slash \
      PLATFORM_CONFIG_PACKAGE=firesim.configs \
      PLATFORM_CONFIG=DefaultV80SlashConfig DESIGN=GCD TARGET_CONFIG=NoConfig \
      VRT_HOME=/opt/SLASH-install \
      fpga_work_dir=/scratch/gcd/delivery \
      GENERATED_DIR=/scratch/gcd/generated OUTPUT_DIR=/scratch/gcd/native \
      replace-rtl

Keep the generated header and driver with this delivery. Rebuilding a driver against
another register map does not qualify that image. The control address block packaged for
the shell must span 128 MiB even when the generated RTL uses fewer address bits; the
build precheck rejects a narrower range.

Implement and qualify without accessing the card
------------------------------------------------

Set ``SLASH_DIR`` to the prepared SDK source and ``XILINX_VIVADO`` to the Vivado
installation prefix, then run bounded implementation and qualification:

.. code-block:: bash

    export SLASH_DIR=/opt/SLASH
    export XILINX_VIVADO=/opt/amd/Vivado/2025.1
    platforms/xilinx_alveo_v80_slash/build-bitstream.sh \
      --build-dir /scratch/gcd/delivery --frequency 40 \
      --timeout-seconds 43200 --log-dir /scratch/gcd/cad-logs
    python3 platforms/xilinx_alveo_v80_slash/cl_firesim/scripts/qualify-build.py \
      --build-dir /scratch/gcd/delivery --frequency 40 \
      --shell /opt/SLASH/linker/slashkit/resources/static_shell/top_wrapper_routed_bb.dcp \
      --static-baseline /opt/firesim-accepted/clock-qualified.dcp \
      --vivado /opt/amd/Vivado/2025.1/bin/vivado \
      --timeout-seconds 28800 --output /scratch/gcd/qualified

Qualification checks routed setup/hold/pulse-width timing, clock and gate-enable
constraints, standard DFX checks, full-context compatibility, and static programming
properties at warning sites. Keep the raw logs and reports, including warnings. This
result is offline qualification only.

Export a candidate using the exact native driver and generated header from that build:

.. code-block:: bash

    python3 platforms/xilinx_alveo_v80_slash/export-deployment.py candidate \
      --qualified /scratch/gcd/qualified \
      --driver /scratch/gcd/native/GCD-xilinx_alveo_v80_slash \
      --header /scratch/gcd/native/build/FireSim-generated.const.h \
      --metadata "firesim-deployquintuplet:xilinx_alveo_v80_slash-midasexamples-GCD-NoConfig-DefaultV80SlashConfig,firesim-commit:$(git rev-parse HEAD)" \
      --output /scratch/gcd/candidate

The candidate records image, driver, register-map and report hashes, with
``board_validated=false``. It cannot be used as an accepted Manager deployment. The
metadata must contain the actual deploy quintuplet in FireSim's comma-separated
``key:value`` format. Use the build's recorded source commit; the example assumes that
``HEAD`` still names that source. Free-form descriptions are rejected.

Controlled physical acceptance
------------------------------

Schedule exclusive access to a healthy SLASH card before this step. The command below
**programs hardware** and runs the candidate. Choose a design-specific functional
expectation, workload and cycle budget; a successful programming transaction alone is
insufficient.

Private host configuration names ``bdf``, ``board_lock``, ``vivado``, ``v80_smi``,
``clk_force``, ``clk_monitor`` and ``clock_probe``, plus optional ``jtag_hw_server``,
``library_path`` and a ``sudo`` argument list. Keep credentials out of this repository
and deployment bundles. The clock probe must use the generated ClockBridge map, measure
the clock and fail on mismatch. Put matching SLASH libraries first in ``library_path``;
acceptance binds the resolved library fingerprints as well as the bundled files.

Install reviewed host executables with these argument and result contracts:

- ``clk_force <bdf> <vbin> <frequency_hz>`` sets the loaded image's clock.
- ``clk_monitor <bdf> <vbin>`` prints the frequency in Hz as an integer on its final
  stdout line, which must exactly match the requested frequency.
- ``clock_probe <vbin> <hCycle_0> <hCycle_1> <hCycle_latch> <frequency_hz>`` measures
  the generated ClockBridge and fails if its reviewed measurement tolerance is exceeded.

All helpers return zero on success and nonzero on failure. The runner supplies the
register offsets and requested frequency. Card selection for ``clock_probe`` belongs to
its host-specific executable or wrapper; configure the same BDF as the host
configuration. Prepare these tools and their card selection before acceptance. They
operate on the image programmed by the runner.

.. code-block:: bash

    python3 /scratch/gcd/candidate/run-qualified.py \
      --bundle /scratch/gcd/candidate \
      --host-config /etc/firesim/v80-runtime.json --bdf 0000:ab:00 \
      --acceptance-test --expect '<required functional output regex>' \
      --timeout-seconds 1800 --log-dir /scratch/gcd/board-run -- \
      +max-cycles=100000

Replace the expectation placeholder before running. The runner supplies the bundle's
image path, BDF and loaded-image flag to its exact native binary. It owns the board lock
and bounded child processes and preserves stdout, stderr and the acceptance result.
Existing drivers or hardware servers prevent launch.

Acceptance requires valid PCIe link speed and width across all three functions before
and after programming, before the driver, and after completion. It also requires
three-SLR configuration/PMC-log audits, clock readback and measurement, functional
output and process cleanup. Enumeration or an apparently healthy management service
alone is insufficient. Unknown speed or width 63 blocks further operations. Failure
preserves a sysfs snapshot and does not initiate an automatic reset, retry or
reprogramming. Coordinate recovery with the host owner if communication is lost.

The native driver polls initialization immediately, then waits one millisecond between
pending responses. Its default deadline is 60 seconds; the native argument
``+init-timeout-ms=N`` accepts one integer from 1 through 600000. Invalid register
values fail immediately. Neither this deadline nor the runner's process timeout can
interrupt an individual stalled bus access or guarantee host isolation.

Promote only a successful, matching evidence record:

.. code-block:: bash

    python3 platforms/xilinx_alveo_v80_slash/export-deployment.py accept \
      --bundle /scratch/gcd/candidate \
      --board-evidence /scratch/gcd/board-run/board-acceptance.json \
      --output /scratch/gcd/accepted

Promotion rechecks the candidate and acceptance log hashes. The resulting
``firesim.tar.gz`` contains a relative ``xilinx_alveo_v80_slash/`` directory. Acceptance
covers that driver, image, workload and runtime libraries. It does not establish other
designs, optional capture modes or a full operating-system boot. An accepted bundle can
be checked after relocation:

.. code-block:: bash

    python3 /scratch/gcd/accepted/xilinx_alveo_v80_slash/run-qualified.py \
      --bundle /scratch/gcd/accepted/xilinx_alveo_v80_slash \
      --bdf 0000:ab:00 --verify-only

This check does not access hardware or load private host configuration.

Manager integration and transport choices
-----------------------------------------

Use ``deploy/bit-builder-recipes/xilinx_alveo_v80_slash.yaml`` with
``XilinxAlveoV80SlashBitBuilder``. Configure its SLASH, Vivado, static-shell,
static-baseline and ``vrt_home`` paths for the build host. Manager snapshots build
inputs and exports an offline candidate; physical acceptance and promotion remain
required before adding the resulting archive to the hardware database.

Select ``XilinxAlveoV80SlashInstanceDeployManager`` through
``deploy/run-farm-recipes/xilinx_alveo_v80_slash.yaml``. The run host's private FPGA
database is a JSON array, for example:

.. code-block:: json

    [{"bdf": "0000:ab:00", "host_config": "/etc/firesim/v80-runtime.json"}]

Manager verifies the accepted bundle, requires its generated register map to match the
selected simulation, launches the accepted native binary, and retains a per-run proof
directory. Its stop operation checks the owned process identity and waits for cleanup.
The current hardware scope is one card per run host.

``DefaultV80SlashConfig`` selects CPU-managed streams over the DDR register transport.
``DefaultV80SlashFPGAManagedConfig`` selects bidirectional FPGA-managed streams sharing
target DDR. ``DefaultV80SlashMMIOConfig`` selects the optional low-bandwidth MMIO stream
widget. Each choice requires its own matching driver, header, image and acceptance
evidence. CPU tests with predicted external DDR or vendor primitives do not substitute
for those physical checks.
