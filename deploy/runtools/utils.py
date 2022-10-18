""" Miscellaneous utils used by other buildtools pieces. """

from __future__ import annotations

import lddwrap
import logging
from os import fspath
from os.path import realpath
from pathlib import Path
from fabric.api import run, warn_only, hide # type: ignore

from typing import List, Tuple, Type

rootLogger = logging.getLogger()

def has_sudo() -> bool:
    with warn_only(), hide('warnings'):
        return run("sudo -ln true").return_code == 0

def get_local_shared_libraries(elf: str) -> List[Tuple[str, str]]:
    """ Given path to executable `exe`, returns a list of path tuples, (A, B), where:
        A is the local file path on the manager instance to the library
        B is the destination file path on the run farm instance relative to the driver

        NOTE: ignores the following dso's reported by ldd:
         * linux-vdso : special dso injected by the kernel, not copyable
         * ld-linux : is the dynamic loader, not copyable
         * known members of glibc.  These could be copyable but
           glibc is very coupled to the kernel version and following the pattern
           of the conda packages we build from, we will not copy glibc around.
           We compile against centos7 glibc and given the backwards compatibility of glibc
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
    rootLogger.debug(f"Identifying ldd dependencies for: {elf}")
    for dso in lddwrap.list_dependencies(Path(elf)):
        if dso.soname is None:
            assert dso.path is not None and '/ld-linux' in fspath(dso.path), f"dynamic linker is only allowed no soname, not: {dso}"
            continue
        if 'linux-vdso.so' in dso.soname:
            continue
        assert dso.path is not None, f"{dso.soname} has no linkage reported by ldd for {elf} in: {dso}"
        if fspath(dso.path) in glibc_shared_libs:
            rootLogger.debug(f"{dso.path} found in glibc_shared_libs. skipping")
            continue
        if fspath(realpath(dso.path)) in glibc_shared_libs:
            rootLogger.debug(f"{dso.path} realpath {realpath(dso.path)} found in glibc_shared_libs. skipping")
            continue

        # we do some moderate evilness here and collapse
        # the version symlinks of our dso into the actual dso
        # so that we have fewer pieces to copy over
        libs.append((realpath(dso.path), dso.soname))

    return libs


class MacAddress():
    """ This class allows globally allocating/assigning MAC addresses.
    It also wraps up the code necessary to get a string version of a mac
    address. The MAC prefix we use here is 00:12:6D, which is assigned to the
    EECS Department at UC Berkeley.

    >>> MacAddress.reset_allocator()
    >>> mac = MacAddress()
    >>> str(mac)
    '00:12:6D:00:00:02'
    >>> mac.as_int_no_prefix()
    2
    >>> mac = MacAddress()
    >>> mac.as_int_no_prefix()
    3
    """
    next_mac_alloc: int = 2
    eecs_mac_prefix: int = 0x00126d000000
    mac_without_prefix_as_int: int
    mac_as_int: int

    def __init__(self) -> None:
        """ Allocate a new mac address, store it, then increment nextmacalloc."""
        assert MacAddress.next_mac_alloc < 2**24, "Too many MAC addresses allocated"
        self.mac_without_prefix_as_int = MacAddress.next_mac_alloc
        self.mac_as_int = MacAddress.eecs_mac_prefix + MacAddress.next_mac_alloc

        # increment for next call
        MacAddress.next_mac_alloc += 1

    def as_int_no_prefix(self) -> int:
        """ Return the MAC address as an int. WITHOUT THE PREFIX!
        Used by the MAC tables in switch models."""
        return self.mac_without_prefix_as_int

    def __str__(self) -> str:
        """ Return the MAC address in the "regular format": colon separated,
        show all leading zeroes."""
        # format as 12 char hex with leading zeroes
        str_ver = format(self.mac_as_int, '012X')
        # split into two hex char chunks
        import re
        split_str_ver = re.findall('..?', str_ver)
        # insert colons
        return ":".join(split_str_ver)

    @classmethod
    def reset_allocator(cls: Type[MacAddress]) -> None:
        """ Reset allocator back to default value. """
        cls.next_mac_alloc = 2

    @classmethod
    def next_mac_to_allocate(cls: Type[MacAddress]) -> int:
        """ Return the next mac that will be allocated. This basically tells you
        how many entries you need in your switching tables. """
        return cls.next_mac_alloc


