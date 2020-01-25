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
import yaml
import re
import pprint

# Useful for defining lists of files (e.g. 'files' part of config)
FileSpec = collections.namedtuple('FileSpec', [ 'src', 'dst' ])

# Global configuration (marshalCtx set by initialize())
ctx = None

# List of marshal submodules (those enabled by init-submodules.sh)
marshalSubmods = [
        'linux-dir',
        'pk-dir',
        'busybox-dir',
        'buildroot-dir',
        'driver-dirs'
        ]

class SubmoduleError(Exception):
    """Error representing a nonexistent or uninitialized submodule"""
    def __init__(self, path):
        self.path = path

    def __repr__(self):
        return 'Submodule Error: ' + self.__str__()

    def __str__(self):
        if self.path in [ ctx[opt] for opt in marshalSubmods ]:
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

class ConfigurationError(Exception):
    """Error representing a generic problem with configuration"""
    def __init__(self, cause):
        self.cause = cause

    def __str__(self):
        return "Configuration Error: " + cause

class ConfigurationOptionError(ConfigurationError):
    """Error representing a problem with marshal configuration."""
    def __init__(self, opt, cause):
        self.opt = opt
        self.cause = cause

    def __str__(self):
        return "Error with configuration option '" + self.opt + "': " + str(self.cause)
        
class ConfigurationFileError(ConfigurationError):
    """Error representing issues with loading the configuration"""
    def __init__(self, missingFile, cause):
        self.missingFile = missingFile
        self.cause = cause

    def __str__(self):
        return "Failed to load configuration file: " + str(self.missingFile) + "\n" + \
                str(self.cause)

def cleanPaths(opts, baseDir=pathlib.Path('.')):
    """Clean all user-defined paths in an options dictionary by converting them
    to resolved, absolute, pathlib.Path's. Paths will be interpreted as
    relative to baseDir."""

    # These options represent pathlib paths
    pathOpts = [
        'board-dir',
        'image-dir',
        'linux-dir',
        'pk-dir',
        'log-dir',
        'res-dir'
    ]

    for opt in pathOpts:
        if opt in opts:
            try:
                path = (baseDir / pathlib.Path(opts[opt])).resolve(strict=True)
                opts[opt] = path
            except Exception as e:
                raise ConfigurationOptionError(opt, "Invalid path: " + str(e))

# These represent all available user-defined options (those set by the
# environment or config files). See default-config.yaml or the documentation
# for the meaning of these options.
userOpts = [
        'board-dir',
        'image-dir',
        'linux-dir',
        'pk-dir',
        'log-dir',
        'res-dir',
        'jlevel',  # int or str from user, converted to '-jN' after loading
        'rootfs-margin', # int or str from user, converted to int bytes after loading
        'doitOpts', # Dictionary of options to pass to doit (for the 'run' section)
        ]

# These represent all available derived options (constants and those generated
# from userOpts, but not directly settable by users)
derivedOpts = [
        # Root for firemarshal (e.g. FireMarshal/)
        'root-dir',

        # Root for wlutil library
        'wlutil-dir',

        # Builtin workloads (the board's bases)
        'workdir-builtin',

        # Busybox source directory (used for the initramfs)
        'busybox-dir',

        # Initramfs root directory (used to build default initramfs for loading board drivers)
        'initramfs-dir',

        # Storage for generated/temporary outputs
        'gen-dir',

        # Empty directory used for mounting images
        'mnt-dir',

        # Path to basic template for user-specified commands (the "command:" option) 
        'command-script',

        # Gets set uniquely for each logical invocation of this library
        'run-name',

        # List of paths to linux driver sources to use
        'driver-dirs',

        # Buildroot source directory
        'buildroot-dir'
        ]

class marshalCtx(collections.MutableMapping):
    """Global FireMarshal context (configuration)."""
    
    # Actual internal storage for all options
    opts = {}

    def __init__(self):
        """On init, we search for and load all sources of options.

        The order in which options are added here is the order of precidence.
        
        Attributes:
            opts: Dictonary containing all configuration options (static values
                set by the user or statically derived from those). Option
                values are documented in the package variables 'derivedOpts' and
                'userOpts'
        """

        # These are set early to help with config file search-paths
        self['wlutil-dir'] = pathlib.Path(__file__).parent.resolve()
        self['root-dir'] = pathlib.Path(sys.modules['__main__'].__file__).parent.resolve()

        # This is an exhaustive list of defaults, it always exists and can be
        # overwritten by other user-defined configs
        defaultCfg = self['wlutil-dir'] / 'default-config.yaml'
        self.addPath(defaultCfg)
        
        # These are mutually-exlusive search paths (only one will be loaded)
        cfgSources = [
            # pwd
            pathlib.Path('marshal-config.yaml'),
            # next to the marshal executable
            self['root-dir'] / 'marshal-config.yaml'
        ]
        for src in cfgSources:
            if src.exists():
                self.addPath(src)
                break

        self.addEnv()

        # We should have all user-defined options now
        missingOpts = set(userOpts) - set(self.opts)
        if len(missingOpts) != 0:
            raise ConfigurationError("Missing required options: " + str(missingOpts))

        self.deriveOpts()

        # It would be a marshal bug if any of these options are missing
        missingDOpts = set(derivedOpts) - set(self.opts)
        if len(missingDOpts) != 0:
            raise RuntimeError("Internal error: Missing derived options or constants: " + str(missingDOpts))

    def add(self, newOpts):
        """Add options to this configuration, opts will override any
        conflicting options.
        
        newOpts: dictionary containing new options to add"""
        
        self.opts = dict(self.opts, **newOpts)
        
    def addPath(self, path):
        """Add the yaml file at path to the config."""

        try:
            with open(path, 'r') as newF:
                newCfg = yaml.full_load(newF)
        except Exception as e:
            raise ConfigurationFileError(path, e)

        cleanPaths(newCfg, baseDir=path.parent)
        self.add(newCfg)

    def addEnv(self):
        """Find all marshal options in the environment and load them.
        
        Environment options take the form MARSHAL_OPT where "OPT" will be
        converted as follows:
            1) convert to lower-case
            2) all underscores will be replaced with dashes

        For example MARSHAL_LINUX_DIR=../special/linux would add a ('linux-dir'
        : '../special/linux') option to the config."""

        reOpt = re.compile("^MARSHAL_(\S+)")
        envCfg = {}
        for opt,val in os.environ.items():
            match = reOpt.match(opt)
            if match:
                optName = match.group(1).lower().replace('_', '-')
                envCfg[optName] = val

        cleanPaths(envCfg)
        self.add(envCfg)

    def deriveOpts(self):
        """Update or initialize all derived options. This assumes all
        user-defined options have been set already. See the 'derivedOpts' list
        above for documentation of these options."""

        self['workdir-builtin'] = self['board-dir'] / 'base-workloads'
        self['busybox-dir'] = self['wlutil-dir'] / 'busybox'
        self['initramfs-dir'] = self['wlutil-dir'] / "initramfs"
        self['gen-dir'] = self['wlutil-dir'] / "generated"
        self['mnt-dir'] = self['root-dir'] / "disk-mount"
        self['command-script'] = self['gen-dir'] / "_command.sh"
        self['run-name'] = ""
        self['rootfs-margin'] = humanfriendly.parse_size(str(self['rootfs-margin']))
        self['jlevel'] = '-j' + str(self['jlevel'])
        self['driver-dirs'] = self['board-dir'].glob('drivers/*')
        self['buildroot-dir'] = self['wlutil-dir'] / 'br' / 'buildroot'

        if self['doitOpts']['dep_file'] == '':
            self['doitOpts']['dep_file'] = str(self['gen-dir'] / 'marshaldb')

    def setRunName(self, configPath, operation):
        """Helper function for formatting a  unique run name. You are free to
        set the 'run-name' option directly if you don't need the help.

        Args:
            configPath (pathlike): Config file used for this run
            operation (str): The operation being performed on this run (e.g. 'build') 
        """

        if configPath:
            configName = pathlib.Path(configPath).stem
        else:
            configName = ''

        timeline = time.strftime("%Y-%m-%d--%H-%M-%S", time.gmtime())
        randname = ''.join(random.choice(string.ascii_uppercase + string.digits) for _ in range(16))

        runName = configName + \
                "-" + operation + \
                "-" + timeline + \
                "-" +  randname

        self['run-name'] = runName

    # The following methods are needed by MutableMapping
    def __getitem__(self, key):
        if key not in self.opts:
            raise ConfigurationOptionError(key, 'No such option')

        return self.opts[key]

    def __setitem__(self, key, value):
        self.opts[key] = value

    def __delitem__(self, key):
        del self.opts[key]

    def __iter__(self):
        return iter(self.opts)

    def __len__(self):
        return len(self.opts)

    def __str__(self):
        return pprint.pformat(self.opts)

    def __repr__(self):
        return repr(self.opts)

def initialize():
    """Get wlutil ready to use. Must be called at least once per installation.
    Is safe and fast to call every time you load the library."""

    global ctx
    ctx = marshalCtx()

    ctx['mnt-dir'].mkdir(parents=True, exist_ok=True)

    # Directories that must be initialized for disk-based initramfs
    initramfs_disk_dirs = ["bin", 'dev', 'etc', 'proc', 'root', 'sbin', 'sys', 'usr/bin', 'usr/sbin', 'mnt/root']

    # Setup disk initramfs dirs
    for d in initramfs_disk_dirs:
        if not (ctx['initramfs-dir'] / 'disk' / d).exists():
            (ctx['initramfs-dir'] / 'disk' / d).mkdir(parents=True)

def getCtx():
    """Return the global confguration object (ctx). This is only valid after
    calling initialize().
    
    Returns (marshalCtx)
    """ 
    return ctx

def getOpt(opt):
    if ctx is None:
        raise RuntimeError("wlutil context not initized")
    else:
        return ctx[opt]

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
    logPath = getOpt('log-dir') / (getOpt('run-name') + '.log')
    
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
    with open(getOpt('command-script'), 'w') as s:
        s.write("#!/bin/bash\n")
        s.write(command + "\n")
        s.write("sync; poweroff -f\n")

    return getOpt('command-script')

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
    sudoCmd = ["/usr/bin/sudo"]
    @contextmanager
    def mountImg(imgPath, mntPath):
        run(sudoCmd + ["mount", "-o", "loop", imgPath, mntPath])
        try:
            yield mntPath
        finally:
            run(sudoCmd + ['umount', mntPath])
else:
    # User doesn't have sudo (use guestmount, slow but reliable)
    sudoCmd = []
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
        p = sp.run(sudoCmd + ["sh", "-c", "find -print0 | cpio --owner root:root --null -ov --format=newc"],
                stderr=sp.PIPE, stdout=outCpio, cwd=src)
        log.debug(p.stderr.decode('utf-8'))

# Apply the overlay directory "overlay" to the filesystem image "img"
# Note that all paths must be absolute
def applyOverlay(img, overlay):
    log = logging.getLogger()
    flist = []
    for f in overlay.glob('*'):
        flist.append(FileSpec(src=f, dst=pathlib.Path('/')))

    copyImgFiles(img, flist, 'in')
    
def resizeFS(img, newSize=0):
    """Resize the rootfs at img to newSize.

    img: path to image file to resize
    newSize: desired size (in bytes). If 0, shrink the image to its minimum
      size + rootfs-margin
    """
    log = logging.getLogger()
    chkfsCmd = ['e2fsck', '-f', '-p', str(img)]
    ret = run(chkfsCmd, check=False).returncode
    if ret >= 4:
        # e2fsck has non-error error codes (1,2 indicate corrected errors)
        raise sp.CalledProcessError(ret, " ".join(chkfsCmd))

    if newSize == 0:
        run(['resize2fs', '-M', img])
        newSize = os.path.getsize(img) + getOpt('rootfs-margin')

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

    with mountImg(img, getOpt('mnt-dir')):
        for f in files:
            if direction == 'in':
                dst = str(getOpt('mnt-dir') / f.dst.relative_to('/'))
                run(sudoCmd + ['cp', '-a', str(f.src), dst])
            elif direction == 'out':
                uid = os.getuid()
                src = str(getOpt('mnt-dir') / f.src.relative_to('/'))
                run(sudoCmd + ['cp', '-a', src, str(f.dst)])
            else:
                raise ValueError("direction option must be either 'in' or 'out'")

_toolVersions = None
def getToolVersions():
    """Detect version information for the currently enabled toolchain."""

    global _toolVersions
    if _toolVersions is None:
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

        _toolVersions = {'linuxMaj' : linuxMaj,
                'linuxMin' : linuxMin,
                'gcc' : toolVer}

    return _toolVersions

# only warn once per-submodule (if it's included by multiple workloads)
checkGitStatusWarned = []
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
        if submodule not in checkGitStatusWarned:
            log.warn("Submodule: " + str(submodule) + " has uncommited changes. Any dependent workloads will be rebuilt")
            checkGitStatusWarned.append(submodule)
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

def noDiskPath(path):
    return path.parent / (path.name + '-nodisk')
