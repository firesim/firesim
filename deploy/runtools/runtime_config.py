""" This file manages the overall configuration of the system for running
simulation tasks. """

from __future__ import print_function

from datetime import timedelta
from time import strftime, gmtime
import configparser
import pprint
import logging
import lddwrap

from fabric.api import *
from awstools.awstools import *
from awstools.afitools import *
from runtools.firesim_topology_with_passes import FireSimTopologyWithPasses
from runtools.workload import WorkloadConfig
from runtools.run_farm import RunFarm
from util.streamlogger import StreamLogger
import os
from os import fspath
from os.path import realpath
from pathlib import Path

LOCAL_DRIVERS_BASE = "../sim/output/f1/"
LOCAL_DRIVERS_GENERATED_SRC = "../sim/generated-src/f1/"
LOCAL_SYSROOT_LIB = "../sim/lib-install/lib/"
CUSTOM_RUNTIMECONFS_BASE = "../sim/custom-runtime-configs/"

rootLogger = logging.getLogger()

class RuntimeHWConfig:
    """ A pythonic version of the entires in config_hwdb.ini """

    def __init__(self, name, hwconfig_dict):
        self.name = name
        self.agfi = hwconfig_dict['agfi']
        self.deploytriplet = hwconfig_dict['deploytripletoverride']
        self.deploytriplet = self.deploytriplet if self.deploytriplet != "None" else None
        if self.deploytriplet is not None:
            rootLogger.warning("Your config_hwdb.ini file is overriding a deploytriplet. Make sure you understand why!")
        self.customruntimeconfig = hwconfig_dict['customruntimeconfig']
        self.customruntimeconfig = self.customruntimeconfig if self.customruntimeconfig != "None" else None
        # note whether we've built a copy of the simulation driver for this hwconf
        self.driver_built = False

    def get_deploytriplet_for_config(self):
        """ Get the deploytriplet for this configuration. This memoizes the request
        to the AWS AGFI API."""
        if self.deploytriplet is not None:
            return self.deploytriplet
        rootLogger.debug("Setting deploytriplet by querying the AGFI's description.")
        self.deploytriplet = get_firesim_tagval_for_agfi(self.agfi,
                                                         'firesim-deploytriplet')
    def get_design_name(self):
        """ Returns the name used to prefix MIDAS-emitted files. (The DESIGN make var) """
        my_deploytriplet = self.get_deploytriplet_for_config()
        my_design = my_deploytriplet.split("-")[0]
        return my_design

    def get_local_driver_binaryname(self):
        """ Get the name of the driver binary. """
        return self.get_design_name() + "-f1"

    def get_local_driver_path(self):
        """ return relative local path of the driver used to run this sim. """
        my_deploytriplet = self.get_deploytriplet_for_config()
        drivers_software_base = LOCAL_DRIVERS_BASE + "/" + my_deploytriplet + "/"
        fpga_driver_local = drivers_software_base + self.get_local_driver_binaryname()
        return fpga_driver_local

    def get_local_shared_libraries(self, exe):
        """ Given path to executable `exe`, returns a list of path tuples, (A, B), where:
            A is the local file path on the manager instance to the library
            B is the destination file path on the runfarm instance relative to the driver

            NOTE: ignores the following dso's reported by ldd:
             * linux-vdso : special dso injected by the kernel, not copyable
             * ld-linux : is the dynamic loader, not copyable
             * known members of glibc.  These could be copyable but
               glibc is very coupled to the kernel version and following the pattern
               of the conda packages we build from, we will not copy glibc around.
               We compile against centos7 glibc and given the backwards compatability of glibc
               should be able to copy everything else to most other hosts
               and they will work from a DSO linker/loader perspective (i.e.
               if you're building a driver for AWS, it doesn't magically work
               on a different platform just because you have libraries that will
               link and load)

               The known members of glibc were taught to firesim on centos7 by running
               `rpm -q -f /lib64/libc.so* --filesbypkg | grep -P '\.so(\.|\s*$)' | perl -ane 'print qq/"$F[$#F]",\n/'`

        """


        glibc_shared_libs = [
            "/usr/lib64/libBrokenLocale.so",
            "/usr/lib64/libanl.so",
            "/usr/lib64/libc.so",
            "/usr/lib64/libcidn.so",
            "/usr/lib64/libcrypt.so",
            "/usr/lib64/libdl.so",
            "/usr/lib64/libm.so",
            "/usr/lib64/libnsl.so",
            "/usr/lib64/libnss_compat.so",
            "/usr/lib64/libnss_db.so",
            "/usr/lib64/libnss_dns.so",
            "/usr/lib64/libnss_files.so",
            "/usr/lib64/libnss_hesiod.so",
            "/usr/lib64/libnss_nis.so",
            "/usr/lib64/libnss_nisplus.so",
            "/usr/lib64/libpthread.so",
            "/usr/lib64/libresolv.so",
            "/usr/lib64/librt.so",
            "/usr/lib64/libthread_db.so",
            "/usr/lib64/libutil.so",
            "/etc/ld.so.cache",
            "/etc/ld.so.conf",
            "/etc/ld.so.conf.d",
            "/lib64/ld-2.17.so",
            "/lib64/ld-linux-x86-64.so.2",
            "/lib64/libBrokenLocale-2.17.so",
            "/lib64/libBrokenLocale.so.1",
            "/lib64/libSegFault.so",
            "/lib64/libanl-2.17.so",
            "/lib64/libanl.so.1",
            "/lib64/libc-2.17.so",
            "/lib64/libc.so.6",
            "/lib64/libcidn-2.17.so",
            "/lib64/libcidn.so.1",
            "/lib64/libcrypt-2.17.so",
            "/lib64/libcrypt.so.1",
            "/lib64/libdl-2.17.so",
            "/lib64/libdl.so.2",
            "/lib64/libm-2.17.so",
            "/lib64/libm.so.6",
            "/lib64/libnsl-2.17.so",
            "/lib64/libnsl.so.1",
            "/lib64/libnss_compat-2.17.so",
            "/lib64/libnss_compat.so.2",
            "/lib64/libnss_db-2.17.so",
            "/lib64/libnss_db.so.2",
            "/lib64/libnss_dns-2.17.so",
            "/lib64/libnss_dns.so.2",
            "/lib64/libnss_files-2.17.so",
            "/lib64/libnss_files.so.2",
            "/lib64/libnss_hesiod-2.17.so",
            "/lib64/libnss_hesiod.so.2",
            "/lib64/libnss_nis-2.17.so",
            "/lib64/libnss_nis.so.2",
            "/lib64/libnss_nisplus-2.17.so",
            "/lib64/libnss_nisplus.so.2",
            "/lib64/libpthread-2.17.so",
            "/lib64/libpthread.so.0",
            "/lib64/libresolv-2.17.so",
            "/lib64/libresolv.so.2",
            "/lib64/librt-2.17.so",
            "/lib64/librt.so.1",
            "/lib64/libthread_db-1.0.so",
            "/lib64/libthread_db.so.1",
            "/lib64/libutil-2.17.so",
            "/lib64/libutil.so.1",
            "/lib64/rtkaio/librt.so.1",
            "/lib64/rtkaio/librtkaio-2.17.so",
            "/usr/lib64/audit/sotruss-lib.so",
            "/usr/lib64/gconv/ANSI_X3.110.so",
            "/usr/lib64/gconv/ARMSCII-8.so",
            "/usr/lib64/gconv/ASMO_449.so",
            "/usr/lib64/gconv/BIG5.so",
            "/usr/lib64/gconv/BIG5HKSCS.so",
            "/usr/lib64/gconv/BRF.so",
            "/usr/lib64/gconv/CP10007.so",
            "/usr/lib64/gconv/CP1125.so",
            "/usr/lib64/gconv/CP1250.so",
            "/usr/lib64/gconv/CP1251.so",
            "/usr/lib64/gconv/CP1252.so",
            "/usr/lib64/gconv/CP1253.so",
            "/usr/lib64/gconv/CP1254.so",
            "/usr/lib64/gconv/CP1255.so",
            "/usr/lib64/gconv/CP1256.so",
            "/usr/lib64/gconv/CP1257.so",
            "/usr/lib64/gconv/CP1258.so",
            "/usr/lib64/gconv/CP737.so",
            "/usr/lib64/gconv/CP770.so",
            "/usr/lib64/gconv/CP771.so",
            "/usr/lib64/gconv/CP772.so",
            "/usr/lib64/gconv/CP773.so",
            "/usr/lib64/gconv/CP774.so",
            "/usr/lib64/gconv/CP775.so",
            "/usr/lib64/gconv/CP932.so",
            "/usr/lib64/gconv/CSN_369103.so",
            "/usr/lib64/gconv/CWI.so",
            "/usr/lib64/gconv/DEC-MCS.so",
            "/usr/lib64/gconv/EBCDIC-AT-DE-A.so",
            "/usr/lib64/gconv/EBCDIC-AT-DE.so",
            "/usr/lib64/gconv/EBCDIC-CA-FR.so",
            "/usr/lib64/gconv/EBCDIC-DK-NO-A.so",
            "/usr/lib64/gconv/EBCDIC-DK-NO.so",
            "/usr/lib64/gconv/EBCDIC-ES-A.so",
            "/usr/lib64/gconv/EBCDIC-ES-S.so",
            "/usr/lib64/gconv/EBCDIC-ES.so",
            "/usr/lib64/gconv/EBCDIC-FI-SE-A.so",
            "/usr/lib64/gconv/EBCDIC-FI-SE.so",
            "/usr/lib64/gconv/EBCDIC-FR.so",
            "/usr/lib64/gconv/EBCDIC-IS-FRISS.so",
            "/usr/lib64/gconv/EBCDIC-IT.so",
            "/usr/lib64/gconv/EBCDIC-PT.so",
            "/usr/lib64/gconv/EBCDIC-UK.so",
            "/usr/lib64/gconv/EBCDIC-US.so",
            "/usr/lib64/gconv/ECMA-CYRILLIC.so",
            "/usr/lib64/gconv/EUC-CN.so",
            "/usr/lib64/gconv/EUC-JISX0213.so",
            "/usr/lib64/gconv/EUC-JP-MS.so",
            "/usr/lib64/gconv/EUC-JP.so",
            "/usr/lib64/gconv/EUC-KR.so",
            "/usr/lib64/gconv/EUC-TW.so",
            "/usr/lib64/gconv/GB18030.so",
            "/usr/lib64/gconv/GBBIG5.so",
            "/usr/lib64/gconv/GBGBK.so",
            "/usr/lib64/gconv/GBK.so",
            "/usr/lib64/gconv/GEORGIAN-ACADEMY.so",
            "/usr/lib64/gconv/GEORGIAN-PS.so",
            "/usr/lib64/gconv/GOST_19768-74.so",
            "/usr/lib64/gconv/GREEK-CCITT.so",
            "/usr/lib64/gconv/GREEK7-OLD.so",
            "/usr/lib64/gconv/GREEK7.so",
            "/usr/lib64/gconv/HP-GREEK8.so",
            "/usr/lib64/gconv/HP-ROMAN8.so",
            "/usr/lib64/gconv/HP-ROMAN9.so",
            "/usr/lib64/gconv/HP-THAI8.so",
            "/usr/lib64/gconv/HP-TURKISH8.so",
            "/usr/lib64/gconv/IBM037.so",
            "/usr/lib64/gconv/IBM038.so",
            "/usr/lib64/gconv/IBM1004.so",
            "/usr/lib64/gconv/IBM1008.so",
            "/usr/lib64/gconv/IBM1008_420.so",
            "/usr/lib64/gconv/IBM1025.so",
            "/usr/lib64/gconv/IBM1026.so",
            "/usr/lib64/gconv/IBM1046.so",
            "/usr/lib64/gconv/IBM1047.so",
            "/usr/lib64/gconv/IBM1097.so",
            "/usr/lib64/gconv/IBM1112.so",
            "/usr/lib64/gconv/IBM1122.so",
            "/usr/lib64/gconv/IBM1123.so",
            "/usr/lib64/gconv/IBM1124.so",
            "/usr/lib64/gconv/IBM1129.so",
            "/usr/lib64/gconv/IBM1130.so",
            "/usr/lib64/gconv/IBM1132.so",
            "/usr/lib64/gconv/IBM1133.so",
            "/usr/lib64/gconv/IBM1137.so",
            "/usr/lib64/gconv/IBM1140.so",
            "/usr/lib64/gconv/IBM1141.so",
            "/usr/lib64/gconv/IBM1142.so",
            "/usr/lib64/gconv/IBM1143.so",
            "/usr/lib64/gconv/IBM1144.so",
            "/usr/lib64/gconv/IBM1145.so",
            "/usr/lib64/gconv/IBM1146.so",
            "/usr/lib64/gconv/IBM1147.so",
            "/usr/lib64/gconv/IBM1148.so",
            "/usr/lib64/gconv/IBM1149.so",
            "/usr/lib64/gconv/IBM1153.so",
            "/usr/lib64/gconv/IBM1154.so",
            "/usr/lib64/gconv/IBM1155.so",
            "/usr/lib64/gconv/IBM1156.so",
            "/usr/lib64/gconv/IBM1157.so",
            "/usr/lib64/gconv/IBM1158.so",
            "/usr/lib64/gconv/IBM1160.so",
            "/usr/lib64/gconv/IBM1161.so",
            "/usr/lib64/gconv/IBM1162.so",
            "/usr/lib64/gconv/IBM1163.so",
            "/usr/lib64/gconv/IBM1164.so",
            "/usr/lib64/gconv/IBM1166.so",
            "/usr/lib64/gconv/IBM1167.so",
            "/usr/lib64/gconv/IBM12712.so",
            "/usr/lib64/gconv/IBM1364.so",
            "/usr/lib64/gconv/IBM1371.so",
            "/usr/lib64/gconv/IBM1388.so",
            "/usr/lib64/gconv/IBM1390.so",
            "/usr/lib64/gconv/IBM1399.so",
            "/usr/lib64/gconv/IBM16804.so",
            "/usr/lib64/gconv/IBM256.so",
            "/usr/lib64/gconv/IBM273.so",
            "/usr/lib64/gconv/IBM274.so",
            "/usr/lib64/gconv/IBM275.so",
            "/usr/lib64/gconv/IBM277.so",
            "/usr/lib64/gconv/IBM278.so",
            "/usr/lib64/gconv/IBM280.so",
            "/usr/lib64/gconv/IBM281.so",
            "/usr/lib64/gconv/IBM284.so",
            "/usr/lib64/gconv/IBM285.so",
            "/usr/lib64/gconv/IBM290.so",
            "/usr/lib64/gconv/IBM297.so",
            "/usr/lib64/gconv/IBM420.so",
            "/usr/lib64/gconv/IBM423.so",
            "/usr/lib64/gconv/IBM424.so",
            "/usr/lib64/gconv/IBM437.so",
            "/usr/lib64/gconv/IBM4517.so",
            "/usr/lib64/gconv/IBM4899.so",
            "/usr/lib64/gconv/IBM4909.so",
            "/usr/lib64/gconv/IBM4971.so",
            "/usr/lib64/gconv/IBM500.so",
            "/usr/lib64/gconv/IBM5347.so",
            "/usr/lib64/gconv/IBM803.so",
            "/usr/lib64/gconv/IBM850.so",
            "/usr/lib64/gconv/IBM851.so",
            "/usr/lib64/gconv/IBM852.so",
            "/usr/lib64/gconv/IBM855.so",
            "/usr/lib64/gconv/IBM856.so",
            "/usr/lib64/gconv/IBM857.so",
            "/usr/lib64/gconv/IBM858.so",
            "/usr/lib64/gconv/IBM860.so",
            "/usr/lib64/gconv/IBM861.so",
            "/usr/lib64/gconv/IBM862.so",
            "/usr/lib64/gconv/IBM863.so",
            "/usr/lib64/gconv/IBM864.so",
            "/usr/lib64/gconv/IBM865.so",
            "/usr/lib64/gconv/IBM866.so",
            "/usr/lib64/gconv/IBM866NAV.so",
            "/usr/lib64/gconv/IBM868.so",
            "/usr/lib64/gconv/IBM869.so",
            "/usr/lib64/gconv/IBM870.so",
            "/usr/lib64/gconv/IBM871.so",
            "/usr/lib64/gconv/IBM874.so",
            "/usr/lib64/gconv/IBM875.so",
            "/usr/lib64/gconv/IBM880.so",
            "/usr/lib64/gconv/IBM891.so",
            "/usr/lib64/gconv/IBM901.so",
            "/usr/lib64/gconv/IBM902.so",
            "/usr/lib64/gconv/IBM903.so",
            "/usr/lib64/gconv/IBM9030.so",
            "/usr/lib64/gconv/IBM904.so",
            "/usr/lib64/gconv/IBM905.so",
            "/usr/lib64/gconv/IBM9066.so",
            "/usr/lib64/gconv/IBM918.so",
            "/usr/lib64/gconv/IBM921.so",
            "/usr/lib64/gconv/IBM922.so",
            "/usr/lib64/gconv/IBM930.so",
            "/usr/lib64/gconv/IBM932.so",
            "/usr/lib64/gconv/IBM933.so",
            "/usr/lib64/gconv/IBM935.so",
            "/usr/lib64/gconv/IBM937.so",
            "/usr/lib64/gconv/IBM939.so",
            "/usr/lib64/gconv/IBM943.so",
            "/usr/lib64/gconv/IBM9448.so",
            "/usr/lib64/gconv/IEC_P27-1.so",
            "/usr/lib64/gconv/INIS-8.so",
            "/usr/lib64/gconv/INIS-CYRILLIC.so",
            "/usr/lib64/gconv/INIS.so",
            "/usr/lib64/gconv/ISIRI-3342.so",
            "/usr/lib64/gconv/ISO-2022-CN-EXT.so",
            "/usr/lib64/gconv/ISO-2022-CN.so",
            "/usr/lib64/gconv/ISO-2022-JP-3.so",
            "/usr/lib64/gconv/ISO-2022-JP.so",
            "/usr/lib64/gconv/ISO-2022-KR.so",
            "/usr/lib64/gconv/ISO-IR-197.so",
            "/usr/lib64/gconv/ISO-IR-209.so",
            "/usr/lib64/gconv/ISO646.so",
            "/usr/lib64/gconv/ISO8859-1.so",
            "/usr/lib64/gconv/ISO8859-10.so",
            "/usr/lib64/gconv/ISO8859-11.so",
            "/usr/lib64/gconv/ISO8859-13.so",
            "/usr/lib64/gconv/ISO8859-14.so",
            "/usr/lib64/gconv/ISO8859-15.so",
            "/usr/lib64/gconv/ISO8859-16.so",
            "/usr/lib64/gconv/ISO8859-2.so",
            "/usr/lib64/gconv/ISO8859-3.so",
            "/usr/lib64/gconv/ISO8859-4.so",
            "/usr/lib64/gconv/ISO8859-5.so",
            "/usr/lib64/gconv/ISO8859-6.so",
            "/usr/lib64/gconv/ISO8859-7.so",
            "/usr/lib64/gconv/ISO8859-8.so",
            "/usr/lib64/gconv/ISO8859-9.so",
            "/usr/lib64/gconv/ISO8859-9E.so",
            "/usr/lib64/gconv/ISO_10367-BOX.so",
            "/usr/lib64/gconv/ISO_11548-1.so",
            "/usr/lib64/gconv/ISO_2033.so",
            "/usr/lib64/gconv/ISO_5427-EXT.so",
            "/usr/lib64/gconv/ISO_5427.so",
            "/usr/lib64/gconv/ISO_5428.so",
            "/usr/lib64/gconv/ISO_6937-2.so",
            "/usr/lib64/gconv/ISO_6937.so",
            "/usr/lib64/gconv/JOHAB.so",
            "/usr/lib64/gconv/KOI-8.so",
            "/usr/lib64/gconv/KOI8-R.so",
            "/usr/lib64/gconv/KOI8-RU.so",
            "/usr/lib64/gconv/KOI8-T.so",
            "/usr/lib64/gconv/KOI8-U.so",
            "/usr/lib64/gconv/LATIN-GREEK-1.so",
            "/usr/lib64/gconv/LATIN-GREEK.so",
            "/usr/lib64/gconv/MAC-CENTRALEUROPE.so",
            "/usr/lib64/gconv/MAC-IS.so",
            "/usr/lib64/gconv/MAC-SAMI.so",
            "/usr/lib64/gconv/MAC-UK.so",
            "/usr/lib64/gconv/MACINTOSH.so",
            "/usr/lib64/gconv/MIK.so",
            "/usr/lib64/gconv/NATS-DANO.so",
            "/usr/lib64/gconv/NATS-SEFI.so",
            "/usr/lib64/gconv/PT154.so",
            "/usr/lib64/gconv/RK1048.so",
            "/usr/lib64/gconv/SAMI-WS2.so",
            "/usr/lib64/gconv/SHIFT_JISX0213.so",
            "/usr/lib64/gconv/SJIS.so",
            "/usr/lib64/gconv/T.61.so",
            "/usr/lib64/gconv/TCVN5712-1.so",
            "/usr/lib64/gconv/TIS-620.so",
            "/usr/lib64/gconv/TSCII.so",
            "/usr/lib64/gconv/UHC.so",
            "/usr/lib64/gconv/UNICODE.so",
            "/usr/lib64/gconv/UTF-16.so",
            "/usr/lib64/gconv/UTF-32.so",
            "/usr/lib64/gconv/UTF-7.so",
            "/usr/lib64/gconv/VISCII.so",
            "/usr/lib64/gconv/libCNS.so",
            "/usr/lib64/gconv/libGB.so",
            "/usr/lib64/gconv/libISOIR165.so",
            "/usr/lib64/gconv/libJIS.so",
            "/usr/lib64/gconv/libJISX0213.so",
            "/usr/lib64/gconv/libKSC.so",
            "/usr/lib64/libmemusage.so",
            "/usr/lib64/libpcprofile.so",
        ]

        libs = list()
        for dso in lddwrap.list_dependencies(Path(exe)):
            if dso.soname is None:
                assert '/ld-linux' in fspath(dso.path), f"dynamic linker is only allowed no soname, not: {dso}"
                continue
            if 'linux-vdso.so' in dso.soname:
                continue
            assert dso.path is not None, f"{dso.soname} has no linkage reported by ldd for {exe} in: {dso}"
            if dso.path in glibc_shared_libs:
                continue

            # we do some moderate evilness here and collapse
            # the version symlinks of our dso into the actual dso
            # so that we have fewer pieces to copy over
            libs.append((realpath(dso.path), dso.soname))

        return libs

    def get_local_runtimeconf_binaryname(self):
        """ Get the name of the runtimeconf file. """
        return "runtime.conf" if self.customruntimeconfig is None else os.path.basename(self.customruntimeconfig)

    def get_local_runtime_conf_path(self):
        """ return relative local path of the runtime conf used to run this sim. """
        my_deploytriplet = self.get_deploytriplet_for_config()
        drivers_software_base = LOCAL_DRIVERS_BASE + "/" + my_deploytriplet + "/"
        my_runtimeconfig = self.customruntimeconfig
        if my_runtimeconfig is None:
            runtime_conf_local = drivers_software_base + "runtime.conf"
        else:
            runtime_conf_local = CUSTOM_RUNTIMECONFS_BASE + my_runtimeconfig
        return runtime_conf_local

    def get_boot_simulation_command(self, slotid, all_macs,
                                              all_rootfses, all_linklatencies,
                                              all_netbws, profile_interval,
                                              all_bootbinaries, trace_enable,
                                              trace_select, trace_start, trace_end,
                                              trace_output_format,
                                              autocounter_readrate, all_shmemportnames,
                                              enable_zerooutdram, disable_asserts,
                                              print_start, print_end,
                                              enable_print_cycle_prefix):
        """ return the command used to boot the simulation. this has to have
        some external params passed to it, because not everything is contained
        in a runtimehwconfig. TODO: maybe runtimehwconfig should be renamed to
        pre-built runtime config? It kinda contains a mix of pre-built and
        runtime parameters currently. """

        # TODO: supernode support
        tracefile = "+tracefile=TRACEFILE" if trace_enable else ""
        autocounterfile = "+autocounter-filename-base=AUTOCOUNTERFILE"

        # this monstrosity boots the simulator, inside screen, inside script
        # the sed is in there to get rid of newlines in runtime confs
        driver = self.get_local_driver_binaryname()
        runtimeconf = self.get_local_runtimeconf_binaryname()

        def array_to_plusargs(valuesarr, plusarg):
            args = []
            for index, arg in enumerate(valuesarr):
                if arg is not None:
                    args.append("""{}{}={}""".format(plusarg, index, arg))
            return " ".join(args) + " "

        def array_to_lognames(values, prefix):
            names = ["{}{}".format(prefix, i) if val is not None else None
                     for (i, val) in enumerate(values)]
            return array_to_plusargs(names, "+" + prefix)

        command_macs = array_to_plusargs(all_macs, "+macaddr")
        command_rootfses = array_to_plusargs(all_rootfses, "+blkdev")
        command_linklatencies = array_to_plusargs(all_linklatencies, "+linklatency")
        command_netbws = array_to_plusargs(all_netbws, "+netbw")
        command_shmemportnames = array_to_plusargs(all_shmemportnames, "+shmemportname")
        command_dromajo = "+drj_dtb=" + all_bootbinaries[0] + ".dtb" +  " +drj_bin=" + all_bootbinaries[0] + " +drj_rom=" + all_bootbinaries[0] + ".rom"

        command_niclogs = array_to_lognames(all_macs, "niclog")
        command_blkdev_logs = array_to_lognames(all_rootfses, "blkdev-log")

        command_bootbinaries = array_to_plusargs(all_bootbinaries, "+prog")
        zero_out_dram = "+zero-out-dram" if (enable_zerooutdram) else ""
        disable_asserts_arg = "+disable-asserts" if (disable_asserts) else ""
        print_cycle_prefix = "+print-no-cycle-prefix" if not enable_print_cycle_prefix else ""

        # TODO supernode support
        dwarf_file_name = "+dwarf-file-name=" + all_bootbinaries[0] + "-dwarf"

        # TODO: supernode support (tracefile, trace-select.. etc)
        basecommand = """screen -S fsim{slotid} -d -m bash -c "script -f -c 'stty intr ^] && sudo sudo LD_LIBRARY_PATH=.:$LD_LIBRARY_PATH ./{driver} +permissive $(sed \':a;N;$!ba;s/\\n/ /g\' {runtimeconf}) +slotid={slotid} +profile-interval={profile_interval} {zero_out_dram} {disable_asserts} {command_macs} {command_rootfses} {command_niclogs} {command_blkdev_logs}  {tracefile} +trace-select={trace_select} +trace-start={trace_start} +trace-end={trace_end} +trace-output-format={trace_output_format} {dwarf_file_name} +autocounter-readrate={autocounter_readrate} {autocounterfile} {command_dromajo} {print_cycle_prefix} +print-start={print_start} +print-end={print_end} {command_linklatencies} {command_netbws}  {command_shmemportnames} +permissive-off {command_bootbinaries} && stty intr ^c' uartlog"; sleep 1""".format(
            slotid=slotid,
            driver=driver,
            runtimeconf=runtimeconf,
            command_macs=command_macs,
            command_rootfses=command_rootfses,
            command_niclogs=command_niclogs,
            command_blkdev_logs=command_blkdev_logs,
            command_linklatencies=command_linklatencies,
            command_netbws=command_netbws,
            profile_interval=profile_interval,
            zero_out_dram=zero_out_dram,
            disable_asserts=disable_asserts_arg,
            command_shmemportnames=command_shmemportnames,
            command_bootbinaries=command_bootbinaries,
            trace_select=trace_select,
            trace_start=trace_start,
            trace_end=trace_end,
            tracefile=tracefile,
            trace_output_format=trace_output_format,
            dwarf_file_name=dwarf_file_name,
            autocounterfile=autocounterfile,
            autocounter_readrate=autocounter_readrate,
            command_dromajo=command_dromajo,
            print_cycle_prefix=print_cycle_prefix,
            print_start=print_start,
            print_end=print_end)

        return basecommand



    def get_kill_simulation_command(self):
        driver = self.get_local_driver_binaryname()
        # Note that pkill only works for names <=15 characters
        return """sudo pkill -SIGKILL {driver}""".format(driver=driver[:15])


    def build_fpga_driver(self):
        """ Build FPGA driver for running simulation """
        if self.driver_built:
            # we already built the driver at some point
            return
        # TODO there is a duplicate of this in runtools
        triplet_pieces = self.get_deploytriplet_for_config().split("-")
        design = triplet_pieces[0]
        target_config = triplet_pieces[1]
        platform_config = triplet_pieces[2]
        rootLogger.info("Building FPGA software driver for " + str(self.get_deploytriplet_for_config()))
        with prefix('cd ../'), \
             prefix('export RISCV={}'.format(os.getenv('RISCV', ""))), \
             prefix('export PATH={}'.format(os.getenv('PATH', ""))), \
             prefix('export LD_LIBRARY_PATH={}'.format(os.getenv('LD_LIBRARY_PATH', ""))), \
             prefix('source ./sourceme-f1-manager.sh'), \
             prefix('cd sim/'), \
             StreamLogger('stdout'), \
             StreamLogger('stderr'):
            localcap = None
            with settings(warn_only=True):
                driverbuildcommand = """make DESIGN={} TARGET_CONFIG={} PLATFORM_CONFIG={} f1""".format(design, target_config, platform_config)
                localcap = local(driverbuildcommand, capture=True)
            rootLogger.debug("[localhost] " + str(localcap))
            rootLogger.debug("[localhost] " + str(localcap.stderr))
            if localcap.failed:
                rootLogger.info("FPGA software driver build failed. Exiting. See log for details.")
                rootLogger.info("""You can also re-run '{}' in the 'firesim/sim' directory to debug this error.""".format(driverbuildcommand))
                exit(1)

        self.driver_built = True


    def __str__(self):
        return """RuntimeHWConfig: {}\nDeployTriplet: {}\nAGFI: {}\nCustomRuntimeConf: {}""".format(self.name, self.deploytriplet, self.agfi, str(self.customruntimeconfig))


class RuntimeHWDB:
    """ This class manages the hardware configurations that are available
    as endpoints on the simulation. """

    def __init__(self, hardwaredbconfigfile):
        agfidb_configfile = configparser.ConfigParser(allow_no_value=True)
        agfidb_configfile.read(hardwaredbconfigfile)
        agfidb_dict = {s:dict(agfidb_configfile.items(s)) for s in agfidb_configfile.sections()}

        self.hwconf_dict = {s: RuntimeHWConfig(s, v) for s, v in agfidb_dict.items()}

    def get_runtimehwconfig_from_name(self, name):
        return self.hwconf_dict[name]

    def __str__(self):
        return pprint.pformat(vars(self))


class InnerRuntimeConfiguration:
    """ Pythonic version of config_runtime.ini """

    def __init__(self, runtimeconfigfile, configoverridedata):
        runtime_configfile = configparser.ConfigParser(allow_no_value=True)
        runtime_configfile.read(runtimeconfigfile)
        runtime_dict = {s:dict(runtime_configfile.items(s)) for s in runtime_configfile.sections()}

        # override parts of the runtime conf if specified
        configoverrideval = configoverridedata
        if configoverrideval != "":
            ## handle overriding part of the runtime conf
            configoverrideval = configoverrideval.split()
            overridesection = configoverrideval[0]
            overridefield = configoverrideval[1]
            overridevalue = configoverrideval[2]
            rootLogger.warning("Overriding part of the runtime config with: ")
            rootLogger.warning("""[{}]""".format(overridesection))
            rootLogger.warning(overridefield + "=" + overridevalue)
            runtime_dict[overridesection][overridefield] = overridevalue

        runfarmtagprefix = "" if 'FIRESIM_RUNFARM_PREFIX' not in os.environ else os.environ['FIRESIM_RUNFARM_PREFIX']
        if runfarmtagprefix != "":
            runfarmtagprefix += "-"

        self.runfarmtag = runfarmtagprefix + runtime_dict['runfarm']['runfarmtag']

        aws_resource_names_dict = aws_resource_names()
        if aws_resource_names_dict['runfarmprefix'] is not None:
            # if specified, further prefix runfarmtag
            self.runfarmtag = aws_resource_names_dict['runfarmprefix'] + "-" + self.runfarmtag

        self.f1_16xlarges_requested = int(runtime_dict['runfarm']['f1_16xlarges']) if 'f1_16xlarges' in runtime_dict['runfarm'] else 0
        self.f1_4xlarges_requested = int(runtime_dict['runfarm']['f1_4xlarges']) if 'f1_4xlarges' in runtime_dict['runfarm'] else 0
        self.m4_16xlarges_requested = int(runtime_dict['runfarm']['m4_16xlarges']) if 'm4_16xlarges' in runtime_dict['runfarm'] else 0
        self.f1_2xlarges_requested = int(runtime_dict['runfarm']['f1_2xlarges']) if 'f1_2xlarges' in runtime_dict['runfarm'] else 0

        if 'launch_instances_timeout_minutes' in runtime_dict['runfarm']:
            self.launch_timeout = timedelta(minutes=int(runtime_dict['runfarm']['launch_instances_timeout_minutes']))
        else:
            self.launch_timeout = timedelta() # default to legacy behavior of not waiting

        self.always_expand = runtime_dict['runfarm'].get('always_expand_runfarm', "yes") == "yes"

        self.run_instance_market = runtime_dict['runfarm']['runinstancemarket']
        self.spot_interruption_behavior = runtime_dict['runfarm']['spotinterruptionbehavior']
        self.spot_max_price = runtime_dict['runfarm']['spotmaxprice']

        self.topology = runtime_dict['targetconfig']['topology']
        self.no_net_num_nodes = int(runtime_dict['targetconfig']['no_net_num_nodes'])
        self.linklatency = int(runtime_dict['targetconfig']['linklatency'])
        self.switchinglatency = int(runtime_dict['targetconfig']['switchinglatency'])
        self.netbandwidth = int(runtime_dict['targetconfig']['netbandwidth'])
        self.profileinterval = int(runtime_dict['targetconfig']['profileinterval'])
        # Default values
        self.trace_enable = False
        self.trace_select = "0"
        self.trace_start = "0"
        self.trace_end = "-1"
        self.trace_output_format = "0"
        self.autocounter_readrate = 0
        self.zerooutdram = False
        self.disable_asserts = False
        self.print_start = "0"
        self.print_end = "-1"
        self.print_cycle_prefix = True

        if 'tracing' in runtime_dict:
            self.trace_enable = runtime_dict['tracing'].get('enable') == "yes"
            self.trace_select = runtime_dict['tracing'].get('selector', "0")
            self.trace_start = runtime_dict['tracing'].get('start', "0")
            self.trace_end = runtime_dict['tracing'].get('end', "-1")
            self.trace_output_format = runtime_dict['tracing'].get('output_format', "0")
        if 'autocounter' in runtime_dict:
            self.autocounter_readrate = int(runtime_dict['autocounter'].get('readrate', "0"))
        self.defaulthwconfig = runtime_dict['targetconfig']['defaulthwconfig']
        if 'hostdebug' in runtime_dict:
            self.zerooutdram = runtime_dict['hostdebug'].get('zerooutdram') == "yes"
            self.disable_asserts = runtime_dict['hostdebug'].get('disable_synth_asserts') == "yes"
        if 'synthprint' in runtime_dict:
            self.print_start = runtime_dict['synthprint'].get("start", "0")
            self.print_end = runtime_dict['synthprint'].get("end", "-1")
            self.print_cycle_prefix = runtime_dict['synthprint'].get("cycleprefix", "yes") == "yes"

        self.workload_name = runtime_dict['workload']['workloadname']
        # an extra tag to differentiate workloads with the same name in results names
        self.suffixtag = runtime_dict['workload']['suffixtag'] if 'suffixtag' in runtime_dict['workload'] else ""
        self.terminateoncompletion = runtime_dict['workload']['terminateoncompletion'] == "yes"

    def __str__(self):
        return pprint.pformat(vars(self))

class RuntimeConfig:
    """ This class manages the overall configuration of the manager for running
    simulation tasks. """

    def __init__(self, args):
        """ This reads runtime configuration files, massages them into formats that
        the rest of the manager expects, and keeps track of other info. """
        self.launch_time = strftime("%Y-%m-%d--%H-%M-%S", gmtime())

        # construct pythonic db of hardware configurations available to us at
        # runtime.
        self.runtimehwdb = RuntimeHWDB(args.hwdbconfigfile)
        rootLogger.debug(self.runtimehwdb)

        self.innerconf = InnerRuntimeConfiguration(args.runtimeconfigfile,
                                                   args.overrideconfigdata)
        rootLogger.debug(self.innerconf)

        # construct a privateip -> instance obj mapping for later use
        #self.instancelookuptable = instance_privateip_lookup_table(
        #    f1_16_instances + f1_2_instances + m4_16_instances)

        # setup workload config obj, aka a list of workloads that can be assigned
        # to a server
        self.workload = WorkloadConfig(self.innerconf.workload_name, self.launch_time,
                                       self.innerconf.suffixtag)

        self.runfarm = RunFarm(self.innerconf.f1_16xlarges_requested,
                               self.innerconf.f1_4xlarges_requested,
                               self.innerconf.f1_2xlarges_requested,
                               self.innerconf.m4_16xlarges_requested,
                               self.innerconf.runfarmtag,
                               self.innerconf.run_instance_market,
                               self.innerconf.spot_interruption_behavior,
                               self.innerconf.spot_max_price,
                               self.innerconf.launch_timeout,
                               self.innerconf.always_expand)

        # start constructing the target configuration tree
        self.firesim_topology_with_passes = FireSimTopologyWithPasses(
            self.innerconf.topology, self.innerconf.no_net_num_nodes,
            self.runfarm, self.runtimehwdb, self.innerconf.defaulthwconfig,
            self.workload, self.innerconf.linklatency,
            self.innerconf.switchinglatency, self.innerconf.netbandwidth,
            self.innerconf.profileinterval, self.innerconf.trace_enable,
            self.innerconf.trace_select, self.innerconf.trace_start, self.innerconf.trace_end,
            self.innerconf.trace_output_format,
            self.innerconf.autocounter_readrate, self.innerconf.terminateoncompletion,
            self.innerconf.zerooutdram, self.innerconf.disable_asserts,
            self.innerconf.print_start, self.innerconf.print_end,
            self.innerconf.print_cycle_prefix)

    def launch_run_farm(self):
        """ directly called by top-level launchrunfarm command. """
        self.runfarm.launch_run_farm()

    def terminate_run_farm(self, terminatesomef1_16, terminatesomef1_4, terminatesomef1_2,
                           terminatesomem4_16, forceterminate):
        """ directly called by top-level terminaterunfarm command. """
        self.runfarm.terminate_run_farm(terminatesomef1_16, terminatesomef1_4, terminatesomef1_2,
                                        terminatesomem4_16, forceterminate)

    def infrasetup(self):
        """ directly called by top-level infrasetup command. """
        # set this to True if you want to use mock boto3 instances for testing
        # the manager.
        use_mock_instances_for_testing = False
        self.firesim_topology_with_passes.infrasetup_passes(use_mock_instances_for_testing)

    def boot(self):
        """ directly called by top-level boot command. """
        use_mock_instances_for_testing = False
        self.firesim_topology_with_passes.boot_simulation_passes(use_mock_instances_for_testing)

    def kill(self):
        use_mock_instances_for_testing = False
        self.firesim_topology_with_passes.kill_simulation_passes(use_mock_instances_for_testing)

    def run_workload(self):
        use_mock_instances_for_testing = False
        self.firesim_topology_with_passes.run_workload_passes(use_mock_instances_for_testing)



