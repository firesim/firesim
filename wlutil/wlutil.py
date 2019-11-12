import os
import subprocess as sp
import logging
import time
import random
import string
import sys
import collections
import shutil
import psutil
import errno
import pathlib
import git
import json
import hashlib
import humanfriendly
from contextlib import contextmanager

#------------------------------------------------------------------------------
# Root Directories:
# wlutil_dir and root_dir are the two roots for wlutil, all other paths are
# derived from these two values. They must work, even when FireMarshal/ is a symlink.
#------------------------------------------------------------------------------
# Root for wlutil library
wlutil_dir = pathlib.Path(__file__).parent.resolve()

# Root for firemarshal (e.g. firesim-software/)
root_dir = pathlib.Path(sys.modules['__main__'].__file__).parent.resolve()

#------------------------------------------------------------------------------
# Derived Paths
#------------------------------------------------------------------------------
# Root for default board (platform-specific stuff)
board_dir = pathlib.Path(root_dir) / 'boards' / 'firechip'

# Builtin workloads
workdir_builtin = board_dir / 'base-workloads'

# Stores all outputs (binaries and images)
image_dir = os.path.join(root_dir, "images")

# Default linux source
linux_dir = os.path.join(root_dir, "riscv-linux")

# Default pk source
pk_dir = root_dir / 'riscv-pk'

# Busybox source directory (used for the initramfs)
busybox_dir = wlutil_dir / 'busybox'

# Initramfs root directory (used to build default initramfs for loading board drivers)
initramfs_dir = pathlib.Path(os.path.join(wlutil_dir, "initramfs"))

# Storage for generated/temporary outputs
gen_dir = wlutil_dir / "generated"

# Runtime Logs
log_dir = os.path.join(root_dir, "logs")

# SW-simulation outputs
res_dir = os.path.join(root_dir, "runOutput")

# Empty directory used for mounting images
mnt = os.path.join(root_dir, "disk-mount")

# Basic template for user-specified commands (the "command:" option) 
commandScript = gen_dir / "_command.sh"

# Default parallelism level to use in subcommands (mostly when calling 'make')
jlevel = "-j" + str(os.cpu_count())

# Gets set uniquely for each logical invocation of this library
runName = ""

# Number of extra bytes to leave free by default in filesystem images
rootfsMargin = 256*(1024*1024)

# Useful for defining lists of files (e.g. 'files' part of config)
FileSpec = collections.namedtuple('FileSpec', [ 'src', 'dst' ])

# List of marshal submodules (those enabled by init-submodules.sh)
marshalSubmods = [
        linux_dir,
        pk_dir,
        busybox_dir,
        wlutil_dir / 'br' / 'buildroot'] + \
        list(board_dir.glob("drivers/*"))
        
class SubmoduleError(Exception):
    """Error representing a nonexistent or uninitialized submodule"""
    def __init__(self, path):
        self.path = path

    def __repr__(self):
        return 'Submodule Error: ' + self.__str__()

    def __str__(self):
        if self.path in marshalSubmods:
            return 'Marshal submodule "' + str(self.path) + \
                    '" not initialized. Please run "./init-submodules.sh."'
        else:
            return "Dependency missing or not initialized " + \
                    str(self.path) + \
                    ". Do you need to initialize a submodule?"

class RootfsCapacityError(Exception):
    """Error representing that the workload's rootfs has run out of disk space."""
    def __init__(self, requested, available):
        self.requested = requested
        self.available = available

    def __str__(self):
        return "Unsufficient disk space: " + \
                "\tRequested: " + humanfriendly.format_size(self.requested) + \
                "\tAvailable: " + humanfriendly.format_size(self.available)

def initialize():
    """Get wlutil ready to use. Must be called at least once per installation.
    Is safe and fast to call every time you load the library."""
    log = logging.getLogger()

    # Directories that must be initialized for disk-based initramfs
    initramfs_disk_dirs = ["bin", 'dev', 'etc', 'proc', 'root', 'sbin', 'sys', 'usr/bin', 'usr/sbin', 'mnt/root']

    # Setup disk initramfs dirs
    for d in initramfs_disk_dirs:
        if not (initramfs_dir / 'disk' / d).exists():
            (initramfs_dir / 'disk' / d).mkdir(parents=True)

# Create a unique run name. You can call this multiple times to reset internal
# paths (e.g. for starting a logically different run). The run name controls
# where logging and workload outputs go. You must call initLogging again to
# reset logging after changing setRunName.
def setRunName(configPath, operation):
    global runName
    
    timeline = time.strftime("%Y-%m-%d--%H-%M-%S", time.gmtime())
    randname = ''.join(random.choice(string.ascii_uppercase + string.digits) for _ in range(16))

    if configPath:
        configName = os.path.splitext(os.path.basename(configPath))[0]
    else:
        configName = ''

    runName = configName + \
            "-" + operation + \
            "-" + timeline + \
            "-" +  randname

def getRunName():
    return runName

# logging setup: You can call this multiple times to reset logging (e.g. if you
# change the RunName)
fileHandler = None
consoleHandler = None
def initLogging(verbose):
    global fileHandler
    global consoleHandler

    rootLogger = logging.getLogger()
    rootLogger.setLevel(logging.NOTSET) # capture everything
    
    # Create a unique log name
    logPath = os.path.join(log_dir, getRunName() + ".log")
    
    # formatting for log to file
    if fileHandler is not None:
        rootLogger.removeHandler(fileHandler)

    fileHandler = logging.FileHandler(str(logPath))
    logFormatter = logging.Formatter("%(asctime)s [%(funcName)-12.12s] [%(levelname)-5.5s]  %(message)s")
    fileHandler.setFormatter(logFormatter)
    fileHandler.setLevel(logging.NOTSET) # log everything to file
    rootLogger.addHandler(fileHandler)

    # log to stdout, without special formatting
    if consoleHandler is not None:
        rootLogger.removeHandler(consoleHandler)

    consoleHandler = logging.StreamHandler(stream=sys.stdout)
    if verbose:
        consoleHandler.setLevel(logging.NOTSET) # show everything
    else:
        consoleHandler.setLevel(logging.INFO) # show only INFO and greater in console

    rootLogger.addHandler(consoleHandler)

# Run subcommands and handle logging etc.
# The arguments are identical to those for subprocess.call()
# level - The logging level to use
# check - Throw an error on non-zero return status?
def run(*args, level=logging.DEBUG, check=True, **kwargs):
    log = logging.getLogger()

    if isinstance(args[0], str):
        prettyCmd = args[0]
    else:
        prettyCmd = ' '.join([str(a) for a in args[0]])

    if 'cwd' in kwargs:
        log.log(level, 'Running: "' + prettyCmd + '" in ' + str(kwargs['cwd']))
    else:
        log.log(level, 'Running: "' + prettyCmd + '" in ' + os.getcwd())

    p = sp.Popen(*args, universal_newlines=True, stderr=sp.STDOUT, stdout=sp.PIPE, **kwargs)
    for line in iter(p.stdout.readline, ''):
        log.log(level, line.strip())
    p.wait()

    if check == True and p.returncode != 0:
            raise sp.CalledProcessError(p.returncode, prettyCmd)

    return p

def genRunScript(command):
    with open(commandScript, 'w') as s:
        s.write("#!/bin/bash\n")
        s.write(command + "\n")
        s.write("sync; poweroff -f\n")

    return commandScript

# This is like os.waitpid, but it works for non-child processes
def waitpid(pid):
    done = False
    while not done:
        try:
            os.kill(pid, 0)
        except OSError as err:
            if err.errno == errno.ESRCH:
                done = True
                break
        time.sleep(0.25)

if sp.run(['/usr/bin/sudo', '-ln', 'true'], stdout=sp.DEVNULL).returncode == 0:
    # User has passwordless sudo available, use the mount command (much faster)
    sudoCmd = "/usr/bin/sudo"
    @contextmanager
    def mountImg(imgPath, mntPath):
        run([sudoCmd,"mount", "-o", "loop", imgPath, mntPath])
        try:
            yield mntPath
        finally:
            run([sudoCmd, 'umount', mntPath])
else:
    # User doesn't have sudo (use guestmount, slow but reliable)
    sudoCmd = ""
    @contextmanager
    def mountImg(imgPath, mntPath):
        run(['guestmount', '--pid-file', 'guestmount.pid', '-a', imgPath, '-m', '/dev/sda', mntPath])
        try:
            with open('./guestmount.pid', 'r') as pidFile:
                mntPid = int(pidFile.readline())
            yield mntPath
        finally:
            run(['guestunmount', mntPath])
            os.remove('./guestmount.pid')

        # There is a race-condition in guestmount where a background task keeps
        # modifying the image for a period after unmount. This is the documented
        # best-practice (see man guestmount).
        waitpid(mntPid)

def toCpio(src, dst):
    log = logging.getLogger()
    log.debug("Creating Cpio archive from " + str(src))
    with open(dst, 'wb') as outCpio:
        p = sp.run([sudoCmd, "sh", "-c", "find -print0 | cpio --owner root:root --null -ov --format=newc"],
                stderr=sp.PIPE, stdout=outCpio, cwd=src)
        log.debug(p.stderr.decode('utf-8'))

# Apply the overlay directory "overlay" to the filesystem image "img"
# Note that all paths must be absolute
def applyOverlay(img, overlay):
    log = logging.getLogger()
    flist = []
    for f in pathlib.Path(overlay).glob('*'):
        flist.append(FileSpec(src=f, dst='/'))

    copyImgFiles(img, flist, 'in')
    
def resizeFS(img, newSize=0):
    """Resize the rootfs at img to newSize.

    img: path to image file to resize
    newSize: desired size (in bytes). If 0, shrink the image to its minimum
      size + rootfsMargin
    """
    log = logging.getLogger()
    chkfsCmd = ['e2fsck', '-f', '-p', str(img)]
    ret = run(chkfsCmd, check=False).returncode
    if ret >= 4:
        # e2fsck has non-error error codes (1,2 indicate corrected errors)
        raise sp.CalledProcessError(ret, " ".join(chkfsCmd))

    if newSize == 0:
        run(['resize2fs', '-M', img])
        newSize = os.path.getsize(img) + rootfsMargin

    origSz = os.path.getsize(img)
    if origSz > newSize:
        log.warn("Cannot shrink image file " + str(img) + \
                ": current size=" + humanfriendly.format_size(origSz, binary=True) + \
                " requested size=" + humanfriendly.format_size(newSize, binary=True))
        return
    elif origSz == newSize:
        return

    os.truncate(img, newSize)
    run(['resize2fs', str(img)])
    return

def copyImgFiles(img, files, direction):
    """Copies a list of type FileSpec ('files') to/from the destination image (img).

    img - path to image file to use
    files - list of FileSpecs to use
    direction - "in" or "out" for copying files into or out of the image (respectively)
    """
    log = logging.getLogger()

    if not os.path.exists(mnt):
        run(['mkdir', mnt])

    with mountImg(img, mnt):
        for f in files:
            if direction == 'in':
                run([sudoCmd, 'cp', '-a', str(f.src), os.path.normpath(mnt + f.dst)])
            elif direction == 'out':
                uid = os.getuid()
                run([sudoCmd, 'cp', '-a', os.path.normpath(mnt + f.src), f.dst])
            else:
                raise ValueError("direction option must be either 'in' or 'out'")

def getToolVersions():
    """Detect version information for the currently enabled toolchain."""

    # We run the preprocessor on a simple program to see the C-visible
    # "LINUX_VERSION_CODE" macro
    linuxHeaderTest = """#include <linux/version.h>
    LINUX_VERSION_CODE
    """
    linuxHeaderVer = sp.run(['riscv64-unknown-linux-gnu-gcc', '-E', '-xc', '-'],
              input=linuxHeaderTest, stdout=sp.PIPE, universal_newlines=True)
    linuxHeaderVer = linuxHeaderVer.stdout.splitlines()[-1].strip()

    # Major/minor version of the linux kernel headers included with our
    # toolchain. This is not necessarily the same as the linux kernel used by
    # Marshal, but is assumed to be <= to the version actually used.
    linuxMaj = str(int(linuxHeaderVer) >> 16)
    linuxMin = str((int(linuxHeaderVer) >> 8) & 0xFF)

    # Toolchain major version
    toolVerStr = sp.run(["riscv64-unknown-linux-gnu-gcc", "--version"],
            universal_newlines=True, stdout=sp.PIPE).stdout
    toolVer = toolVerStr[36]

    return {'linuxMaj' : linuxMaj,
            'linuxMin' : linuxMin,
            'gcc' : toolVer}

def checkGitStatus(submodule):
    """Returns a dictionary representing the status of a git repo.

    submodule: Path to the submodule to check. This check will be skipped if
        the empty string is passed (the empty string is always uptodate)

    Fields:
    'sha'  : latest git commit hash (or "" if not initialized)
    'dirty': boolean indicating whether there are uncommited changes (uninitialized repos are considered dirty)
    'init' : boolean indiicating if the repository has been initialized
    'rebuild' : A random number if the repo should be considered not up to date
        for any reason (e.g. dirty==True or init==False). 0 otherwised.

    This is primarily useful as an input to doit's config_changed() updtodate
    helper which considers a workload not uptodate if some string or dictionary
    has changed since the last time it ran. The 'sha' or 'rebuild' fields will
    change if the repo has changed (or we can't tell if it's changed)."""

    log = logging.getLogger()

    if submodule == None:
        return {
                'sha' : "",
                'dirty' : False,
                "init" : False,
                "rebuild" : ""
                }

    try:
        repo = git.Repo(submodule)
    except (git.InvalidGitRepositoryError, git.exc.NoSuchPathError):
        # Submodule not initialized (or otherwise can't be read as a repo)
        return {
                'sha' : "",
                'dirty' : True,
                "init" : False,
                "rebuild" : random.random()
                }

    status = {
            'init' : True,
            'sha' : repo.head.object.hexsha,
            'dirty' : repo.is_dirty()
            }
    if repo.is_dirty():
        # In the absense of a clever way to record changes, we must assume that
        # a dirty repo has changed since the last time we built.
        log.warn("Submodule: " + str(submodule) + " has uncommited changes. Any dependent workloads will be rebuilt")
        status['rebuild'] = random.random()
    else:
        status['rebuild'] = 0

    return status

def checkSubmodule(s):
    """Check whether a submodule is present and initialized.

    It is safe to call this on something that is not actually a submodule. In
    that case, this will simply check if the directory is empty or not.

    s: Pathlib path to submodule

    raises SubmoduleError if submodule not ready 
    """
    
    if not s.exists() or not any(os.scandir(s)):
        raise SubmoduleError(s)

# The doit.tools.config_changed helper doesn't support multiple invocations in
# a single uptodate. I fix that bug here, otherwise it's a direct copy from their
# code. See https://github.com/pydoit/doit/issues/333.
class config_changed(object):
    """check if passed config was modified
    @var config (str) or (dict)
    @var encoder (json.JSONEncoder) Encoder used to convert non-default values.
    """
    def __init__(self, config, encoder=None):
        self.config = config
        self.config_digest = None
        self.encoder = encoder

    def _calc_digest(self):
        if isinstance(self.config, str):
            return self.config
        elif isinstance(self.config, dict):
            data = json.dumps(self.config, sort_keys=True, cls=self.encoder)
            byte_data = data.encode("utf-8")
            return hashlib.md5(byte_data).hexdigest()
        else:
            raise Exception(('Invalid type of config_changed parameter got %s' +
                             ', must be string or dict') % (type(self.config),))

    def configure_task(self, task):
        # Give this object a unique ID that persists between calls (ID is the
        # order in which it was evaluated when adding)
        if not hasattr(task, '_config_changed_lastID'):
            task._config_changed_lastID = 0
        self.saverID = str(task._config_changed_lastID)
        task._config_changed_lastID += 1

        configKey = '_config_changed'+self.saverID
        task.value_savers.append(lambda: {configKey:self.config_digest})

    def __call__(self, task, values):
        """return True if config values are UNCHANGED"""

        configKey = '_config_changed'+self.saverID

        self.config_digest = self._calc_digest()
        last_success = values.get(configKey)
        if last_success is None:
            return False
        return (last_success == self.config_digest)
