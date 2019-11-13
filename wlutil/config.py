import os
import glob
from .br import br
from .fedora import fedora as fed
from .baremetal import bare
import collections
import json
import pprint
import logging
import humanfriendly
from .wlutil import *
import pathlib as pth

# This is a comprehensive list of all user-defined config options
# Note that paths direct from a config file are relative to workdir, but will
# be converted to absolute during parsing. All paths after loading a config
# will be absolute.
configUser = [
        # Human-readable name for this config
        'name',
        # Path to config to base off (or 'fedora'/'br' if deriving from a base config)
        'base',
        # Path to spike binary to use (use $PATH if this is omitted)
        'spike',
        # Optional extra arguments to spike
        'spike-args',
        # Optional extra arguments to qemu
        'qemu-args',
        # Path to riscv-linux source to use (defaults to the included linux)
        'linux-src',
        # Path to linux configuration file to use
        'linux-config',
        # Path to script to run on host before building this config
        'host-init',
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
        # Size of root filesystem (human-readable string) 
        'rootfs-size'
        ]

# This is a comprehensive list of all options set during config parsing
# (but not explicitly provided by the user)
configDerived = [
        'img', # Path to output filesystem image
        'img-sz', # Desired size of image in bytes (optional)
        'bin', # Path to output binary (e.g. bbl-vmlinux)
        'builder', # A handle to the base-distro object (e.g. br.Builder)
        'base-img', # The filesystem image to use when building this workload
        'base-format', # The format of base-img
        'cfg-file', # Path to this workloads raw config file
        'distro', # Base linux distribution (either 'fedora' or 'br')
        'initramfs', # boolean: should we use an initramfs with this config?
        'jobs', # After parsing, jobs is a collections.OrderedDict containing 'Config' objects for each job.
        'base-deps' # A list of tasks that this workload needs from its base (a potentially empty list)
        ]

# These are the user-defined options that should be converted to absolute
# paths (from workload-relative). Derived options are already absolute.
configToAbs = ['guest-init', 'overlay', 'linux-src', 'linux-config', 'host-init', 'cfg-file', 'bin', 'img', 'spike', 'post_run_hook']

# These are the options that should be inherited from base configs (if not
# explicitly provided)
configInherit = [
        'runSpec',
        'files',
        'outputs',
        'linux-src',
        'linux-config',
        'builder',
        'distro',
        'spike',
        'launch',
        'bin',
        'post_run_hook',
        'spike-args',
        'rootfs-size',
        'qemu-args']

# These are the permissible base-distributions to use (they get treated special)
distros = {
        'fedora' : fed.Builder(),
        'br' : br.Builder(),
        'bare' : bare.Builder()
        }

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

class Config(collections.MutableMapping):
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
    def __init__(self, cfgFile=None, cfgDict=None):
        if cfgFile != None:
            with open(cfgFile, 'r') as f:
                self.cfg = json.load(f)
            self.cfg['cfg-file'] = cfgFile
        else:
            self.cfg = cfgDict
            
        cfgDir = None
        if 'cfg-file' in self.cfg:
            cfgDir = self.cfg['cfg-file'].parent
        else:
            assert ('workdir' in self.cfg), "No workdir or cfg-file provided"
            assert ( os.path.isabs(self.cfg['workdir'])), "'workdir' must be absolute for hard-coded configurations (i.e. those without a config file)"

        # Some default values
        self.cfg['base-deps'] = []

        if 'workdir' in self.cfg:
            self.cfg['workdir'] = pathlib.Path(self.cfg['workdir'])
            if not self.cfg['workdir'].is_absolute():
                self.cfg['workdir'] = cfgDir / self.cfg['workdir']
        else:
            self.cfg['workdir'] = cfgDir / self.cfg['name']

        if 'nodisk' not in self.cfg:
            # Note that sw_manager may set this back to true if the user passes command line options
            self.cfg['nodisk'] = False

        # Convert stuff to absolute paths (this should happen as early as
        # possible because the next steps all assume absolute paths)
        for k in (set(configToAbs) & set(self.cfg.keys())):
            if not os.path.isabs(self.cfg[k]):
                self.cfg[k] = self.cfg['workdir'] / self.cfg[k]
            else:
                self.cfg[k] = pathlib.Path(self.cfg[k])

        if 'rootfs-size' in self.cfg:
            self.cfg['img-sz'] = humanfriendly.parse_size(self.cfg['rootfs-size'])

        # Convert files to namedtuple and expand source paths to absolute (dest is already absolute to rootfs) 
        if 'files' in self.cfg:
            fList = []
            for f in self.cfg['files']:
                fList.append(FileSpec(src=self.cfg['workdir'] / f[0], dst=pathlib.Path(f[1])))

            self.cfg['files'] = fList
        
        if 'outputs' in self.cfg:
            self.cfg['outputs'] = [ pathlib.Path(f) for f in self.cfg['outputs'] ]

        # This object handles setting up the 'run' and 'command' options
        if 'run' in self.cfg:
            # Split the args from the script path
            scriptParts = self.cfg['run'].split(' ')
            self.cfg['runSpec'] = RunSpec(
                    script = (self.cfg['workdir'] / scriptParts[0]).resolve(),
                    args = scriptParts[1:])
        elif 'command' in self.cfg:
            self.cfg['runSpec'] = RunSpec(command=self.cfg['command'])

        if 'guest-init' in self.cfg:
            self.cfg['guest-init'] = RunSpec(script=self.cfg['guest-init'])

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
            
    # Finalize this config using baseCfg (which is assumed to be fully
    # initialized).
    def applyBase(self, baseCfg):
        # For any heritable trait that is defined in baseCfg but not self.cfg
        for k in ((set(baseCfg.keys()) - set(self.cfg.keys())) & set(configInherit)):
            self.cfg[k] = baseCfg[k]
        
        # Distros always specify an image if they use one. We assume that this
        # config will not generate a new image if it's base didn't
        if 'img' in baseCfg:
            self.cfg['base-img'] = baseCfg['img']
            self.cfg['base-deps'].append(str(self.cfg['base-img']))
            self.cfg['img'] = getOpt('image-dir') / (self.cfg['name'] + ".img")

        if 'host-init' in baseCfg:
            self.cfg['base-deps'].append(str(baseCfg['host-init']))

        if 'linux-src' not in self.cfg:
            self.cfg['linux-src'] = getOpt('linux-dir')

        # if 'bin' not in self.cfg:
        # We inherit the parent's binary for bare-metal configs, but not linux configs
        # XXX This probably needs to be re-thought out. It's needed at least for including bare-metal binaries as a base for a job.
        if 'linux-config' in self.cfg or 'bin' not in self.cfg:
        # if 'linux-config' in self.cfg:
            self.cfg['bin'] = getOpt('image-dir') / (self.cfg['name'] + "-bin")

        # Some defaults need to occur, even if you don't have a base
        if 'launch' not in self.cfg:
            self.cfg['launch'] = True

        if 'runSpec' not in self.cfg:
            self.cfg['run'] = getOpt('wlutil-dir') / 'null_run.sh'
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

# The configuration of sw-manager is derived from the *.json files in workloads/
class ConfigManager(collections.MutableMapping):
    # This contains all currently loaded configs, indexed by config file path
    cfgs = {}

    # Initialize this class with the set of configs to use. Note that configs
    # that don't parse will issue a warning but be ignored otherwise.
    # Args:
    #   cfgdir - An iterable of directories containing config files to load.
    #            All files matching *.json in these directories will be loaded.
    #   paths - An iterable of absolute paths to config files to load
    def __init__(self, dirs=None, paths=None):
        log = logging.getLogger()
        cfgPaths = []
        if paths != None:
            cfgPaths += paths
        
        if dirs != None:
            for d in dirs: 
                for cfgFile in d.glob('*.json'):
                    cfgPaths.append(cfgFile)

        # First, load the base-configs specially. Note that these are indexed
        # by their names instead of a config path so that users can just pass
        # that instead of a path to a config
        for dName,dBuilder in distros.items():
            log.debug("Loading distro " + dName)
            self.cfgs[dName] = Config(cfgDict=dBuilder.baseConfig())
            self.cfgs[dName].initialized = True

        # Read all the configs from their files
        for f in cfgPaths:
            cfgName = f.name
            try:
                log.debug("Loading ", f)
                if cfgName in list(self.cfgs.keys()):
                    log.warning("Workload " + f + " overrides " + self.cfgs[cfgName]['cfg-file'])
                self.cfgs[cfgName] = Config(f)
            except KeyError as e:
                log.warning("Skipping " + f + ":")
                log.warning("\tMissing required option '" + e.args[0] + "'")
                del self.cfgs[cfgName]
                # raise
                continue
            except Exception as e:
                log.warning("Skipping " + f + ": Unable to parse config:")
                log.warning("\t" + repr(e))
                del self.cfgs[cfgName]
                raise
                continue

        # Now we recursively fill in defaults from base configs
        for f in list(self.cfgs.keys()):
            try:
                self._initializeFromBase(self.cfgs[f])
            except KeyError as e:
                log.warning("Skipping " + f + ":")
                log.warning("\tMissing required option '" + e.args[0] + "'")
                del self.cfgs[f]
                # raise
                continue
            except Exception as e:
                log.warning("Skipping " + f + ": Unable to parse config:")
                log.warning("\t" + repr(e))
                del self.cfgs[f]
                raise
                continue

            log.debug("Loaded " + f)

    # Finish initializing this config from it's base config. Will recursively
    # initialize any needed bases.
    def _initializeFromBase(self, cfg):
        log = logging.getLogger()
        if cfg.initialized == True:
            # Memoizaaaaaaation!
            return
        else:
            if 'base' in cfg:
                try:
                    baseCfg = self.cfgs[cfg['base']]
                except KeyError as e:
                    if e.args[0] != 'base' and e.args[0] == cfg['base']:
                        log.warning("Base config '" + cfg['base'] + " not found.")
                    raise

                if baseCfg.initialized == False:
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

