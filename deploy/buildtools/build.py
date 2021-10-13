from __future__ import with_statement
import json
import time
import random
import string
import logging
import os

from fabric.api import *
from fabric.contrib.console import confirm
from fabric.contrib.project import rsync_project
from awstools.afitools import *
from awstools.awstools import send_firesim_notification
from util.streamlogger import StreamLogger, InfoStreamLogger

rootLogger = logging.getLogger()

def get_deploy_dir():
    """ Must use local here. determine where the firesim/deploy dir is """
    with StreamLogger('stdout'), StreamLogger('stderr'):
        deploydir = local("pwd", capture=True)
    return deploydir

def replace_rtl(build_config):
    """ Generate Verilog """
    rootLogger.info("Building Verilog for {}".format(str(build_config.get_chisel_triplet())))
    with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
        run("{}/general-scripts/replace-rtl.sh {} {} {} {} \"{}\"".format(
            get_deploy_dir() + "/buildtools",
            os.getenv('RISCV', ""),
            os.getenv('PATH', ""),
            os.getenv('LD_LIBRARY_PATH', ""),
            get_deploy_dir() + "/..",
            build_config.make_recipe("PLATFORM=f1 replace-rtl")))

def build_driver(build_config):
    """ Build FPGA driver """
    rootLogger.info("Building FPGA driver for {}".format(str(build_config.get_chisel_triplet())))
    with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
        run("{}/general-scripts/build-driver.sh {} {} {} {} \"{}\"".format(
            get_deploy_dir() + "/buildtools",
            os.getenv('RISCV', ""),
            os.getenv('PATH', ""),
            os.getenv('LD_LIBRARY_PATH', ""),
            get_deploy_dir() + "/..",
            build_config.make_recipe("PLATFORM=f1 driver")))
