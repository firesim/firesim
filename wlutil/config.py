import collections
import yaml
import pprint
import logging
import humanfriendly as hf
from . import wlutil
import pathlib
import copy

# This is a comprehensive list of all user-defined config options
# Note that paths direct from a config file are relative to workdir, but will
# be converted to absolute during parsing. All paths after loading a config
# will be absolute.
configUser = [
        # Human-readable name for this config
        'name',
        # Path to config to base off (or 'fedora'/'br' if deriving from a base config)
        'base',
        # List of job configurations
        'jobs',
        # Path to spike binary to use (use $PATH if this is omitted)
        'spike',
        # Optional extra arguments to spike
        'spike-args',
        # Path to qemu binary to use (use $PATH if this is omitted)
        'qemu',
        # Optional extra arguments to qemu
        'qemu-args',
        # Hard-coded binary path
        'bin',
        # Grouped linux options see the docs for subfields
        'linux',
        # Grouped firmware-related options
        'firmware',
        # Grouped distro-related options
        'distro',
        # Path to script to run on host before building this config
        'host-init',
        # Path to script to run on host after building the binary
        'post-bin',
        # Script to run on results dir after running workload
        'post_run_hook',
        # Path to folder containing overlay files to apply to img
        'overlay',
        # List of tuples of files to add [(guest_dest, host_src),...]
        'files',
        # List of files to copy out of the image after running it. Files will
        # be flattened into results dir. [guest_src0, guest_src1, ...]
        'outputs',
        # Path to script to run on the guest every time it boots
        'run',
        # An inline command to run at startup (cannot be set along with 'run')
        'command',
        # Path to script to run on the guest exactly once when building
        'guest-init',
        # Path to directory for this workload, all user-provided paths will
        # be relative to this (but converted to absolute when loaded)
        'workdir',
        # (bool) Should we launch this config? Defaults to 'true'. Mostly used for jobs.
        'launch',
        # Hard-coded path to image file
        'img',
        # Size of root filesystem (human-readable string)
        'rootfs-size',
        # Number of CPU cores to simulate (applies only to functional simulation). Converted to int after loading.
        'cpus',
        # Amount of memory (DRAM) to use when simulating (applies only to functional simulation).
        # Can be standard size-formatted string from user (e.g. '4G'), but
        # converted to bytes as int after loading.
        'mem',
        # Testing-related options
        'testing',
        # Flag to indicate the workload is a distro (rather than a derived workload).
        'isDistro',
        # The builder object from the distro. This option is only set by distros.
        'builder'
        ]

# Config options only used internally by distro internal configs (defined in the distro python package itself)
configDistro = [
        # Name of the distribution (e.g. 'br', 'fedora', etc)
        'name',
        # Any options to pass to the builder, the value is opaque to marshal core
        'opts',
]

# Deprecated options, will be translated to current equivalents early on in
# loading. They can be ignored after that.
configDeprecated = [
        'linux-config',
        'linux-src',
        'pk-src'
        ]

# This is a comprehensive list of all options set during config parsing
# (but not explicitly provided by the user)
configDerived = [
        'out-dir',  # Path to outputs (filesystem images, binaries, extra metadata)
        'img',  # Path to output filesystem image
        'img-sz',  # Desired size of image in bytes (optional)
        'bin',  # Path to output binary (e.g. bbl-vmlinux)
        'dwarf',  # Additional debugging symbols for the kernel (bbl strips them from 'bin')
        'base-img',  # The filesystem image to use when building this workload
        'base-format',  # The format of base-img
        'cfg-file',  # Path to this workloads raw config file
        'initramfs',  # boolean: should we use an initramfs with this config?
        'jobs',  # After parsing, jobs is a collections.OrderedDict containing 'Config' objects for each job.
        'base-deps',  # A list of tasks that this workload needs from its base (a potentially empty list)
        'firmware-src',  # A convenience field that points to whatever firmware is configured (see 'use-bbl' to determine which it is)
        'use-parent-bin',  # Child would build the exact same binary as the parent, just copy it instead of rebuilding.
        'img-hardcoded',  # The workload hard-coded an image, we will blindly use it.
        ]

# These are the user-defined options that should be converted to absolute
# paths (from workload-relative). Derived options are already absolute.
configToAbs = ['overlay', 'bbl-src', 'cfg-file', 'bin', 'img', 'spike', 'qemu']


# These are the options that should be inherited from base configs (if not
# explicitly provided). Additional options may also be inherited if they require
# more advanced inheritance semantics (e.g. linux-config is a list that needs
# to be appended).
configInherit = [
        'runSpec',
        'files',
        'outputs',
        'bbl-src',
        'bbl-build-args',
        'opensbi-src',
        'opensbi-build-args',
        'builder',
        'distro',
        'spike',
        'qemu',
        'launch',
        'out-dir',
        'bin',
        'img',
        'img-hardcoded',
        'post_run_hook',
        'spike-args',
        'rootfs-size',
        'qemu-args',
        'cpus',
        'mem']

# Default constants, may be overridden by the user
# These take the post-processing form (e.g. if the user can provide a string,
# but we convert it to an int, this would be an int)
configDefaults = {
        'img-sz': 0,  # default to 'tight' configuration
        'mem': hf.parse_size('16GiB'),  # same as firesim default target
        'cpus': 4  # same as firesim default target
        }

# Members of the 'linux' option in the config
configLinux = [
        "source",  # Path to linux source code to use
        "config",  # Path to kfrag to apply over bases
        "modules"  # Dictionary of kernel modules to build and load {MODULE_NAME : PATH_TO_MODULE}
        ]

# Members of the 'firmware' option
configFirmware = [
        "use-bbl",  # Use bbl as firmware instead of openSBI
        "bbl-src",  # Alternative source directory for bbl
        "bbl-build-args",  # Additional arguments to configure script for bbl. User provides string, cannonical form is list.
        "opensbi-src",  # Alternative source directory for openSBI
        "opensbi-build-args",  # Additional arguments to make for openSBI. User provides string, cannonical form is list.
        ]

# Members of the 'testing' option
configTesting = [
        # Directory containing reference outputs
        'refDir',
        # Timeout for building, test will fail after this time
        'buildTimeout',
        # Timeout for running the workload, test will fail after this time
        'runTimeout',
        # Strip as much non-deterministic and irrelevant output from the uartlog before comparing
        'strip'
]


class WorkloadConfigError(Exception):
    def __init__(self, path, opt=None, extra=None):
        self.path = path
        self.opt = opt
        self.extra = extra

    def __str__(self):
        msg = "Unable to load workload " + str(self.path)
        if self.opt is not None:
            msg += f": error with option '{self.opt}'"

        if self.extra is not None:
            msg += ": " + self.extra

        return msg


class RunSpec():
    def __init__(self, script=None, command=None, args=[]):
        """RunSpec represents a command or script to run in the target.

        Args:
            script (pathlib.Path): Path to the script to add.
            command (str): Shell command to run on the target (from /)
            args (iterable(str)): Arguments to append to the script
        """
        if script is not None and command is not None:
            raise ValueError("'command' and 'run' options are mutually exclusive")

        self.args = args
        self.path = script
        self.command = command

    @classmethod
    def fromString(self, command, baseDir=pathlib.Path('.')):
        """Construct a RunSpec from a string representing the script+args to run.

        command: String representing the command to run, e.g. "./script.sh foo bar"
        baseDir: Optional directory in which the script is to be found
        """
        scriptParts = command.split(' ')
        return RunSpec(script=(baseDir / scriptParts[0]).resolve(),
                       args=scriptParts[1:])

    def __repr__(self):
        return "RunSpec(" + \
                ' path: ' + str(self.path) + \
                ', command: ' + str(self.command) + \
                ', args ' + str(self.args) + \
                ')'

    def __str__(self):
        if self.command is not None:
            return self.command
        elif self.path is not None:
            return str(self.path) + " " + ' '.join(self.args)
        else:
            return "uninitialized"


def cleanPath(path, workdir):
    """Take a string or pathlib path argument and return a pathlib.Path
    representing the final absolute path to that option"""
    if path is not None:
        path = pathlib.Path(path)
        if not path.is_absolute():
            path = workdir / path
    return path


def translateDeprecated(config):
    """Replace all deprecated options with their more current equivalents. This
    function has no dependencies on prior parsing of configs, it can run
    against the raw config dict from the user. After this, there will be only
    one cannonical representation for each option and deprecated options will
    not be present in the config."""
    log = logging.getLogger()

    # linux stuff
    # Handle deprecated standalone linux-config and linux-src options (they now live entirely in the linux dict)
    if 'linux' not in config:
        if 'linux-config' in config or 'linux-src' in config:
            config['linux'] = {}
            if 'linux-src' in config:
                config['linux']['source'] = config['linux-src']
            if 'linux-config' in config:
                config['linux']['config'] = config['linux-config']
    elif 'linux-config' in config or 'linux-src' in config:
        log.warning("The deprecated 'linux-config' and 'linux-src' options are mutually exclusive with the 'linux' option; ignoring")

    # Firmware stuff
    if 'pk-src' in config:
        if 'firmware' not in config:
            config['firmware'] = {'bbl-src': config['pk-src']}
        else:
            log.warning("The deprecated 'pk-src' option is mutually exclusive with the 'firmware' option; ignoring")

    # Now that they're translated, remove all deprecated options from config
    for opt in configDeprecated:
        config.pop(opt, None)


def verifyConfig(config):
    """Check that the config passes basic sanity checks and doesn't contain any
    undefined or obviously invalid options. More detailed checking is scattered
    throughout the loading code, this is just for obvious easy-to-check stuff
    on the raw config"""

    log = logging.getLogger()

    for k, v in config.items():
        if k not in (configUser + configDeprecated + configDistro + ["cfg-file"]):
            log.warning("Unrecognized Option: " + k)

    if 'linux' in config:
        for k, v in config['linux'].items():
            if k not in configLinux:
                log.warning("Unrecognized Option: " + k)

    if 'firmware' in config:
        for k, v in config['firmware'].items():
            if k not in configFirmware:
                log.warning("Unrecognized Option: " + k)

    if 'testing' in config:
        for k, v in config['testing'].items():
            if k not in configTesting:
                log.warning("Unrecognized Option: " + k)


def initLinuxOpts(config):
    """Initialize the 'linux' option group of config"""
    if 'linux' not in config:
        return

    if 'config' in config['linux']:
        if isinstance(config['linux']['config'], list):
            config['linux']['config'] = [cleanPath(p, config['workdir']) for p in config['linux']['config']]
        else:
            config['linux']['config'] = [cleanPath(config['linux']['config'], config['workdir'])]

    if 'source' in config['linux']:
        config['linux']['source'] = cleanPath(config['linux']['source'], config['workdir'])

    if 'modules' in config['linux']:
        for name, path in config['linux']['modules'].items():
            if path is None:
                continue
            else:
                config['linux']['modules'][name] = cleanPath(path, config['workdir'])


def inheritLinuxOpts(config, baseCfg):
    """Apply the linux options from baseCfg to config. This also finalizes
    the linux config, including applying defaults (which can't be applied
    until we've inherited)."""

    if 'linux' not in config and 'linux' in baseCfg:
        config['linux'] = copy.deepcopy(baseCfg['linux'])
    elif 'linux' in config and 'linux' in baseCfg:
        # both have a 'linux' option, handle inheritance for each suboption
        if 'config' in baseCfg['linux'] and 'config' in config['linux']:
            # Order matters here! Later kfrags take precedence over earlier.
            config['linux']['config'] = baseCfg['linux']['config'] + config['linux']['config']

        if 'modules' in baseCfg['linux'] and 'modules' in config['linux']:
            config['linux']['modules'] = {**baseCfg['linux']['modules'], **config['linux']['modules']}

        for k, v in baseCfg['linux'].items():
            if k not in config['linux']:
                config['linux'][k] = copy.copy(v)

        for name, src in list(config['linux']['modules'].items()):
            if src is None:
                del config['linux']['modules'][name]


def initFirmwareOpts(config):
    """Initialize the 'firmware' option group"""
    if 'firmware' not in config:
        return

    for opt in ['bbl-src', 'opensbi-src']:
        if opt in config['firmware']:
            config['firmware'][opt] = cleanPath(config['firmware'][opt], config['workdir'])

    for opt in ['bbl-build-args', 'opensbi-build-args']:
        if opt in config['firmware']:
            config['firmware'][opt] = config['firmware'][opt].split()


def inheritFirmwareOpts(config, baseCfg):
    """Apply the firmware options from baseCfg to config."""

    if 'firmware' not in config and 'firmware' in baseCfg:
        config['firmware'] = copy.deepcopy(baseCfg['firmware'])
    elif 'firmware' in config and 'firmware' in baseCfg:
        for k, v in baseCfg['firmware'].items():
            if k not in config['firmware']:
                config['firmware'][k] = copy.copy(v)
            elif k in ['bbl-build-args', 'opensbi-build-args']:
                config['firmware'][k] = baseCfg['firmware'][k] + config['firmware'][k]

    if 'firmware' in config:
        if config['firmware'].get('use-bbl', False):
            config['firmware']['source'] = config['firmware']['bbl-src']
        else:
            config['firmware']['source'] = config['firmware']['opensbi-src']


class Config(collections.abc.MutableMapping):
    # Configs are assumed to be partially initialized until this is explicitly
    # set.
    initialized = False

    # Loads a config file and performs basic parsing and default-value initialization
    # Does not recursively parse base configs.
    # cfgFile - path to config file to load
    # cfgDict - path to pre-initialized dictionary to load
    #   * All paths should be absolute when using a pre-initialized dict
    # Note: cfgDict and cfgFile are mutually exclusive, but you must set one
    # Post:
    #   - All paths will be absolute
    #   - Jobs will be a dictionary of { 'name' : Config } for each job
    #   - All options will be in the cannonical form or not in the dictionary if undefined
    def __init__(self, cfgFile=None, cfgDict=None):
        if cfgFile is not None:
            with open(cfgFile, 'r') as f:
                self.cfg = yaml.safe_load(f)
            self.cfg['cfg-file'] = cfgFile
        else:
            self.cfg = cfgDict

        verifyConfig(self.cfg)
        translateDeprecated(self.cfg)

        cfgDir = None
        if 'cfg-file' in self.cfg:
            cfgDir = self.cfg['cfg-file'].parent

        if 'workdir' in self.cfg:
            self.cfg['workdir'] = pathlib.Path(self.cfg['workdir'])
            if not self.cfg['workdir'].is_absolute():
                assert ('cfg-file' in self.cfg), "'workdir' must be absolute for hard-coded configurations (i.e. those without a config file)"
                self.cfg['workdir'] = cfgDir / self.cfg['workdir']
        else:
            assert ('cfg-file' in self.cfg), "No workdir or cfg-file provided"
            self.cfg['workdir'] = cfgDir / self.cfg['name']

        # Convert stuff to absolute paths (this should happen as early as
        # possible because the next steps all assume absolute paths)
        for k in (set(configToAbs) & set(self.cfg.keys())):
            self.cfg[k] = cleanPath(self.cfg[k], self.cfg['workdir'])

        initLinuxOpts(self.cfg)
        initFirmwareOpts(self.cfg)

        # Some default values
        self.cfg['base-deps'] = []
        self.cfg['use-parent-bin'] = False

        if 'isDistro' not in self.cfg:
            self.cfg['isDistro'] = False

        if 'distro' in self.cfg:
            wlutil.getOpt('distro-mods')[self.cfg['distro']['name']].initOpts(self)

        if 'nodisk' not in self.cfg:
            # Note that sw_manager may set this back to true if the user passes command line options
            self.cfg['nodisk'] = False

        if 'img' in self.cfg:
            self.cfg['img-hardcoded'] = True
        else:
            self.cfg['img-hardcoded'] = False

        if 'rootfs-size' in self.cfg:
            self.cfg['img-sz'] = hf.parse_size(str(self.cfg['rootfs-size']))
        else:
            self.cfg['img-sz'] = configDefaults['img-sz']

        # Convert files to namedtuple and expand source paths to absolute (dest is already absolute to rootfs)
        if 'files' in self.cfg:
            fList = []
            for f in self.cfg['files']:
                fList.append(wlutil.FileSpec(src=self.cfg['workdir'] / f[0], dst=pathlib.Path(f[1])))

            self.cfg['files'] = fList

        if 'outputs' in self.cfg:
            self.cfg['outputs'] = [pathlib.Path(f) for f in self.cfg['outputs']]

        if 'out-dir' in self.cfg:
            self.cfg['out-dir'] = wlutil.getOpt('image-dir') / self.cfg['out-dir']
        else:
            self.cfg['out-dir'] = wlutil.getOpt('image-dir') / self.cfg['name']

        # This object handles setting up the 'run' and 'command' options
        if 'run' in self.cfg:
            self.cfg['runSpec'] = RunSpec.fromString(
                    self.cfg['run'],
                    baseDir=self.cfg['workdir'])

        elif 'command' in self.cfg:
            self.cfg['runSpec'] = RunSpec(command=self.cfg['command'])

        # Handle script arguments
        for sOpt in ['guest-init', 'post_run_hook', 'host-init', 'post-bin']:
            if sOpt in self.cfg:
                self.cfg[sOpt] = RunSpec.fromString(
                        self.cfg[sOpt],
                        baseDir=self.cfg['workdir'])

        if 'mem' in self.cfg:
            self.cfg['mem'] = hf.parse_size(str(self.cfg['mem']))
        else:
            self.cfg['mem'] = configDefaults['mem']

        if 'cpus' in self.cfg:
            self.cfg['cpus'] = int(self.cfg['cpus'])
        else:
            self.cfg['cpus'] = configDefaults['cpus']

        # A leaf has customized the distro in some way (rather than just
        # inheriting a distro config)
        if 'distro' in self.cfg:
            self.cfg['distro']['leaf'] = True

        # Convert jobs to standalone configs
        if 'jobs' in self.cfg:
            jList = self.cfg['jobs']
            self.cfg['jobs'] = collections.OrderedDict()

            for jCfg in jList:
                jCfg['workdir'] = self.cfg['workdir']
                # TODO come up with a better scheme here, name is used to
                # derive the img and bin names, but naming jobs this way makes
                # for ugly hacks later when looking them up.
                jCfg['name'] = self.cfg['name'] + '-' + jCfg['name']
                jCfg['cfg-file'] = self.cfg['cfg-file']

                # jobs can base off any workload, but default to the current workload
                if 'base' not in jCfg:
                    jCfg['base'] = cfgFile.name

                self.cfg['jobs'][jCfg['name']] = Config(cfgDict=jCfg)

    def applyBase(self, baseCfg):
        """Finalize this config using baseCfg (which is assumed to be fully
           initialized)."""

        # For any heritable trait that is defined in baseCfg but not self.cfg
        for k in ((set(baseCfg.keys()) - set(self.cfg.keys())) & set(configInherit)):
            self.cfg[k] = baseCfg[k]

        # Distros always specify an image if they use one. We assume that this
        # config will not generate a new image if it's base didn't
        if 'img' in baseCfg:
            self.cfg['base-img'] = baseCfg['img']
            self.cfg['base-deps'].append(str(self.cfg['base-img']))
            self.cfg['img'] = self.cfg['out-dir'] / (self.cfg['name'] + ".img")

        if 'bin' in baseCfg:
            self.cfg['base-bin'] = baseCfg['bin']

        if 'dwarf' in baseCfg:
            self.cfg['base-dwarf'] = baseCfg['dwarf']

        if 'host-init' in baseCfg:
            self.cfg['base-deps'].append(str(baseCfg['host-init']))

        inheritLinuxOpts(self.cfg, baseCfg)
        inheritFirmwareOpts(self.cfg, baseCfg)

        if 'linux' in self.cfg:
            # Linux workloads get their own binary, whether from scratch or a
            # copy of their parent's
            self.cfg['bin'] = self.cfg['out-dir'] / (self.cfg['name'] + "-bin")
            self.cfg['dwarf'] = self.cfg['out-dir'] / (self.cfg['name'] + "-bin-dwarf")

            # To avoid needlessly recompiling kernels, we check if the child has
            # the exact same binary-related configuration. If 'use-parent-bin'
            # is set, buildBin will simply copy the parent's binary rather than
            # compiling it from scratch.
            self.cfg['use-parent-bin'] = True
            for opt in ['firmware', 'linux', 'host-init']:
                if opt not in self.cfg:
                    # Child doesn't overwrite a non-heritable option
                    continue
                elif self.cfg.get(opt, None) != baseCfg.get(opt, None):
                    self.cfg['use-parent-bin'] = False
        else:
            # bare-metal workloads use the parent's binary directly rather than
            # copying it like a Linux workload would
            self.cfg['use-parent-bin'] = False

        # Some defaults need to occur, even if you don't have a base
        if 'launch' not in self.cfg:
            self.cfg['launch'] = True

        if 'runSpec' not in self.cfg:
            self.cfg['run'] = wlutil.getOpt('wlutil-dir') / 'null_run.sh'
            self.cfg['runSpec'] = RunSpec(script=self.cfg['run'])

    # The following methods are needed by MutableMapping
    def __getitem__(self, key):
        return self.cfg[key]

    def __setitem__(self, key, value):
        self.cfg[key] = value

    def __delitem__(self, key):
        del self.cfg[key]

    def __iter__(self):
        return iter(self.cfg)

    def __len__(self):
        return len(self.cfg)

    def __str__(self):
        return pprint.pformat(self.cfg)

    def __repr__(self):
        return repr(self.cfg)


def findConfig(targetName, searchPaths):
    for searchDir in searchPaths:
        for candidate in searchDir.iterdir():
            if candidate.name == targetName:
                return candidate

    return None


# The configuration of sw-manager is derived from the *.json files in workloads/
class ConfigManager(collections.abc.MutableMapping):
    # This contains all currently loaded configs, indexed by config file path
    cfgs = {}

    def __init__(self, configNames, searchPaths):
        """Initialize this class with the set of configs to use. Note that configs
        that don't parse will issue a warning but be ignored otherwise.
        Args:
        configNames - An iterable of configuration file names to load, names
            may be a string (in which case it will be looked for in searchPaths) or
            a pathlib.Path object (in which case it will be loaded directly).

        searchPaths - A list of pathlib.Path objects to directories to search
            when locating workload configurations.
        """
        log = logging.getLogger()

        self.searchPaths = searchPaths

        # Load the explicitly provided workloads
        for cfgName in configNames:
            if isinstance(cfgName, pathlib.Path):
                cfgPath = cfgName
            else:
                cfgPath = findConfig(cfgName, searchPaths)

            if cfgPath is None:
                raise WorkloadConfigError(cfgName, extra="Could not locate workload")

            cfgName = cfgPath.name

            log.debug(f"Loading {cfgName}:{cfgPath}")
            if cfgName in self.cfgs:
                log.warning("Workload " + str(cfgPath) + " overrides " + str(self.cfgs[cfgName]['cfg-file']))

            targetCfg = Config(cfgPath)
            self.cfgs[cfgName] = targetCfg

            # Once loaded, jobs are pretty much like any other workload. We
            # stick them in the global pool of loaded configs for future
            # reference.
            if 'jobs' in targetCfg:
                for jCfg in targetCfg['jobs'].values():
                    self.cfgs[jCfg['name']] = jCfg

        # Load parent workloads
        for targetCfg in list(self.cfgs.values()):
            parentName = targetCfg.get('base', None)
            currentName = cfgName
            while not (parentName in self.cfgs or
                       parentName is None or
                       parentName in wlutil.getOpt('distro-mods')):

                parentPath = findConfig(parentName, searchPaths + [targetCfg['cfg-file'].parent])
                if parentPath is None:
                    raise WorkloadConfigError(currentName,
                                              opt='base',
                                              extra=f"Could not locate base workload '{parentName}'")

                parentCfg = Config(parentPath)
                self.cfgs[parentName] = parentCfg
                currentName = parentName
                parentName = parentCfg.get('base', None)

        # Distro options essentially fork the inheritance tree at the root
        # since they modify a parent from the child. For every child with a
        # custom distro configuration, we create new configs for its parents
        # all the way down to the distro. After this, they are separate
        # workloads for all intents and purposes.
        for cfgName in list(self.cfgs.keys()):
            cfg = self.cfgs[cfgName]
            # Leafs modified the distro config in some way and need to fork the
            # inheritance chain
            if 'distro' in cfg and cfg['distro']['leaf']:
                self._forkDistro(cfg)

        # Now we recursively fill in defaults from base configs.
        for cfgName in list(self.cfgs.keys()):
            cfg = self.cfgs[cfgName]

            try:
                self._initializeFromBase(self.cfgs[cfgName])
            except KeyError as e:
                raise WorkloadConfigError(cfgName,
                                          extra=f"Missing required option '{e.args[0]}'")
            except Exception as e:
                raise WorkloadConfigError(cfgName,
                                          extra="Unable to parse config: " + repr(e))

            log.debug("Loaded " + str(cfgName))

    def _forkDistro(self, cfg):
        """Starting from cfg, create new versions of every base that have this
        config's distro options. After this, cfg will inherit from distro-opt
        specific configs."""
        log = logging.getLogger()

        distCfg = cfg['distro']
        distMod = wlutil.getOpt('distro-mods')[distCfg['name']]

        def mergeOptsRecursive(distMod, cfg):
            if cfg['isDistro']:
                return None
            elif 'base' not in cfg:
                return cfg['distro']['opts']
            else:
                baseOpts = mergeOptsRecursive(distMod, self.cfgs[cfg['base']])
                if 'distro' not in cfg:
                    return baseOpts
                elif baseOpts is None:
                    return cfg['distro']['opts']
                else:
                    return distMod.mergeOpts(baseOpts, cfg['distro']['opts'])

        distCfg['opts'] = mergeOptsRecursive(distMod, cfg)
        distID = distMod.hashOpts(distCfg['opts'])

        def forkRecursive(cfg, distID):
            # If there is no explicit base, the workload must be inheriting from a
            # distro directly, find or create the distro workload for it.
            if 'base' not in cfg or self.cfgs[cfg['base']]['isDistro']:
                if 'distro' not in cfg:
                    raise ValueError("The 'distro' option is required for workloads that do not have a base")

                distName = cfg['distro']['name']
                distMod = wlutil.getOpt('distro-mods')[distName]

                if distID is not None:
                    baseName = distName + "." + distID
                else:
                    baseName = distName

                if baseName not in self.cfgs.keys():
                    log.debug("Creating distro {} : {}".format(distName, distID))
                    distObj = distMod.Builder(cfg['distro']['opts'])
                    distWorkload = Config(cfgDict=distObj.getWorkload())
                    distWorkload.initialized = True
                    self.cfgs[baseName] = distWorkload

                cfg['base'] = baseName
                return
            else:
                if distID is not None:
                    forkedBaseName = cfg['base'] + "." + distID
                else:
                    forkedBaseName = cfg['base']

                if forkedBaseName in self.cfgs:
                    cfg['base'] = forkedBaseName
                    return

                forkedBase = copy.deepcopy(self.cfgs[cfg['base']])
                forkedBase['name'] = forkedBaseName
                forkedBase['distro'] = cfg['distro']

                self.cfgs[forkedBaseName] = forkedBase
                cfg['base'] = forkedBaseName

                forkRecursive(self.cfgs[cfg['base']], distID)
                return

        forkRecursive(cfg, distID)

    def _initializeFromBase(self, cfg):
        """Finish initializing this config from it's base config. Will recursively
           initialize any needed bases."""
        log = logging.getLogger()
        if cfg.initialized:
            # Memoizaaaaaaation!
            return
        else:
            if 'base' in cfg:
                try:
                    baseCfg = self.cfgs[cfg['base']]
                except KeyError as e:
                    if e.args[0] != 'base' and e.args[0] == cfg['base']:
                        log.warning("Base config '" + cfg['base'] + "' not found.")
                    raise

                if not baseCfg.initialized:
                    self._initializeFromBase(baseCfg)

                cfg.applyBase(baseCfg)
            else:
                # Some defaults need to occur, even if you don't have a base
                if 'launch' not in cfg:
                    cfg['launch'] = True

            # must set initialized to True before handling jobs because jobs
            # will reference this config (we'd infinite loop without memoization)
            cfg.initialized = True

            # Now that this config is initialized, finalize jobs
            if 'jobs' in cfg:
                for jCfg in cfg['jobs'].values():
                    self._initializeFromBase(jCfg)

    # The following methods are needed by MutableMapping
    def keys(self):
        return self.cfgs.keys()

    def __getitem__(self, key):
        return self.cfgs[key]

    def __setitem__(self, key, value):
        self.cfgs[key] = value

    def __delitem__(self, key):
        del self.cfgs[key]

    def __iter__(self):
        return iter(self.cfgs)

    def __len__(self):
        return len(self.cfgs)

    def __str__(self):
        return pprint.pformat(self.cfgs)

    def __repr__(self):
        return repr(self.cfgs)
