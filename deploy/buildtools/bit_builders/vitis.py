from __future__ import with_statement, annotations

import logging
import os
from fabric.api import prefix, local, run # type: ignore
from fabric.contrib.project import rsync_project # type: ignore

from util.streamlogger import StreamLogger, InfoStreamLogger
from buildtools.bitbuilder import BitBuilder

# imports needed for python type checking
from typing import Optional, Dict, Any, TYPE_CHECKING
if TYPE_CHECKING:
    from buildtools.buildconfig import BuildConfig

rootLogger = logging.getLogger()

def get_deploy_dir() -> str:
    """Determine where the firesim/deploy directory is and return its path.

    Returns:
        Path to firesim/deploy directory.
    """
    with StreamLogger('stdout'), StreamLogger('stderr'):
        deploydir = local("pwd", capture=True)
    return deploydir

class VitisBitBuilder(BitBuilder):
    """Bit builder class that builds a Vitis bitstream from the build config.

    Attributes:
    """

    def __init__(self, build_config: BuildConfig, args: Dict[str, Any]) -> None:
        super().__init__(build_config, args)

    def replace_rtl(self):
        rootLogger.info(f"Building Verilog for {self.build_config.get_chisel_triplet()}")

        with prefix(f'cd {get_deploy_dir()}/../'), \
            prefix(f'export RISCV={os.getenv("RISCV", "")}'), \
            prefix(f'export PATH={os.getenv("PATH", "")}'), \
            prefix(f'export LD_LIBRARY_PATH={os.getenv("LD_LIBRARY_PATH", "")}'), \
            prefix('source sourceme-f1-manager.sh'), \
            prefix('cd sim/'), \
            InfoStreamLogger('stdout'), \
            InfoStreamLogger('stderr'):
            run(self.build_config.make_recipe("PLATFORM=vitis replace-rtl"))

    def build_driver(self):
        rootLogger.info("Building FPGA driver for {}".format(str(self.build_config.get_chisel_triplet())))

        with prefix(f'cd {get_deploy_dir()}/../'), \
            prefix(f'export RISCV={os.getenv("RISCV", "")}'), \
            prefix(f'export PATH={os.getenv("PATH", "")}'), \
            prefix(f'export LD_LIBRARY_PATH={os.getenv("LD_LIBRARY_PATH", "")}'), \
            prefix('source sourceme-f1-manager.sh'), \
            prefix('cd sim/'), \
            InfoStreamLogger('stdout'), \
            InfoStreamLogger('stderr'):
            run(self.build_config.make_recipe("PLATFORM=vitis driver"))

    def cl_dir_setup(self, chisel_triplet: str, dest_build_dir: str) -> str:
        """Setup CL_DIR on build host.

        Args:
            chisel_triplet: Build config chisel triplet used to uniquely identify build dir.
            dest_build_dir: Destination base directory to use.

        Returns:
            Path to CL_DIR directory (that is setup) or `None` if invalid.
        """
        fpga_build_postfix = f"cl_{chisel_triplet}"

        # local paths
        local_vitis_dir = f"{get_deploy_dir()}/../platforms/vitis"

        dest_vitis_dir = "{}/platforms/vitis".format(dest_build_dir)

        # copy vitis to the build instance.
        # do the rsync, but ignore any checkpoints that might exist on this machine
        # (in case builds were run locally)
        # extra_opts -l preserves symlinks
        with StreamLogger('stdout'), StreamLogger('stderr'):
            run('mkdir -p {}'.format(dest_vitis_dir))
            rsync_cap = rsync_project(
                local_dir=local_vitis_dir,
                remote_dir=dest_vitis_dir,
                ssh_opts="-o StrictHostKeyChecking=no",
                exclude="cl_*",
                extra_opts="-l", capture=True)
            rootLogger.debug(rsync_cap)
            rootLogger.debug(rsync_cap.stderr)
            rsync_cap = rsync_project(
                local_dir="{}/{}/".format(local_vitis_dir, fpga_build_postfix),
                remote_dir='{}/{}'.format(dest_vitis_dir, fpga_build_postfix),
                ssh_opts="-o StrictHostKeyChecking=no",
                extra_opts="-l", capture=True)
            rootLogger.debug(rsync_cap)
            rootLogger.debug(rsync_cap.stderr)

        return f"{dest_vitis_dir}/{fpga_build_postfix}"

    def build_bitstream(self, bypass: bool = False) -> None:
        """ Run Vivado, generate Vitis bitstream. Then terminate the instance at the end.

        Args:
            bypass: If true, immediately return and terminate build host. Used for testing purposes.
        """
        if bypass:
            self.build_config.build_config_file.build_farm.release_build_host(self.build_config)
            return

        # The default error-handling procedure. Send an email and teardown instance
        def on_build_failure():
            """ Terminate build host and notify user that build failed """

            message_title = "FireSim Vitis FPGA Build Failed"

            message_body = "Your FPGA build failed for triplet: " + self.build_config.get_chisel_triplet()

            send_firesim_notification(message_title, message_body)

            rootLogger.info(message_title)
            rootLogger.info(message_body)

            self.build_config.build_config_file.build_farm.release_build_host(self.build_config)

        rootLogger.info("Building Vitis Bitstream from Verilog")

        local_deploy_dir = get_deploy_dir()
        fpga_build_postfix = f"cl_{self.build_config.get_chisel_triplet()}"
        local_results_dir = f"{local_deploy_dir}/results-build/{self.build_config.get_build_dir_name()}"

        build_farm = self.build_config.build_config_file.build_farm

        # 'cl_dir' holds the eventual directory in which vivado will run.
        cl_dir = self.cl_dir_setup(self.build_config.get_chisel_triplet(), build_farm.get_build_host(self.build_config).dest_build_dir)

        # TODO: Does this still apply or is this done in the Makefile
        ## copy over generated RTL into local CL_DIR before remote
        #with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
        #    run("""mkdir -p {}""".format(local_results_dir))
        #    run("""cp {}/design/FireSim-generated.sv {}/FireSim-generated.sv""".format(cl_dir, local_results_dir))

        vitis_result = 0
        with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
            # TODO: Put script within Vitis area
            # copy script to the cl_dir and execute
            rsync_cap = rsync_project(
                local_dir=f"{local_deploy_dir}/../platforms/vitis/build-bitstream.sh",
                remote_dir=f"{cl_dir}/",
                ssh_opts="-o StrictHostKeyChecking=no",
                extra_opts="-l", capture=True)
            rootLogger.debug(rsync_cap)
            rootLogger.debug(rsync_cap.stderr)

            vitis_result = run(f"{cl_dir}/build-bitstream.sh {cl_dir}").return_code

        # put build results in the result-build area
        with StreamLogger('stdout'), StreamLogger('stderr'):
            rsync_cap = rsync_project(
                local_dir=f"{local_results_dir}/",
                remote_dir=cl_dir,
                ssh_opts="-o StrictHostKeyChecking=no", upload=False, extra_opts="-l",
                capture=True)
            rootLogger.debug(rsync_cap)
            rootLogger.debug(rsync_cap.stderr)

        if vitis_result != 0:
            on_build_failure()
            return

        finame = self.build_config.name
        xclbin_path = cl_dir + "/bitstream/build_dir.xilinx_u250_gen3x16_xdma_3_1_202020_1/firesim.xclbin"

        results_build_dir = """{}/""".format(local_results_dir)

        hwdb_entry = finame + ":\n"
        hwdb_entry += "    xclbin: " + xclbin_path + "\n"
        hwdb_entry += "    deploy_triplet_override: null\n"
        hwdb_entry += "    custom_runtime_config: null\n"

        message_title = "FireSim FPGA Build Completed"
        message_body = "Your FI has been created!\nAdd\n\n" + hwdb_entry + "\nto your config_hwdb.ini to use this hardware configuration."

        rootLogger.info(message_title)
        rootLogger.info(message_body)

        # for convenience when generating a bunch of images. you can just
        # cat all the files in this directory after your builds finish to get
        # all the entries to copy into config_hwdb.yaml
        hwdb_entry_file_location = f"{local_deploy_dir}/built-hwdb-entries/"
        local("mkdir -p " + hwdb_entry_file_location)
        with open(hwdb_entry_file_location + "/" + finame, "w") as outputfile:
            outputfile.write(hwdb_entry)

        if self.build_config.post_build_hook:
            with StreamLogger('stdout'), StreamLogger('stderr'):
                localcap = local(f"{self.build_config.post_build_hook} {local_results_dir}", capture=True)
                rootLogger.debug("[localhost] " + str(localcap))
                rootLogger.debug("[localhost] " + str(localcap.stderr))

        rootLogger.info(f"Build complete! Vitis FI ready. See {os.path.join(hwdb_entry_file_location,finame)}.")

        self.build_config.build_config_file.build_farm.release_build_host(self.build_config)
