import os
import glob
import br.br as br
import fedora.fedora as fed
import baremetal.bare as bare
import collections
import json
import pprint
import logging
from util.util import *
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
        # Path to riscv-linux source to use (defaults to the included linux)
        'linux-src',
        # Path to linux configuration file to use
        'linux-config',
        # Path to script to run on host before building this config
        'host-init',
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
        'workdir'
        ]

# This is a comprehensive list of all options set during config parsing
# (but not explicitly provided by the user)
configDerived = [
        'img', # Path to output filesystem image
        'bin', # Path to output binary (e.g. bbl-vmlinux)
        'builder', # A handle to the base-distro object (e.g. br.Builder)
        'base-img', # The filesystem image to use when building this workload
        'base-format', # The format of base-img
        'cfg-file', # Path to this workloads raw config file
        'distro', # Base linux distribution (either 'fedora' or 'br')
        ]

# These are the user-defined options that should be converted to absolute
# paths (from workload-relative). Derived options are already absolute.
configToAbs = ['guest-init', 'run', 'overlay', 'linux-src', 'linux-config', 'host-init', 'cfg-file', 'bin', 'img']

# These are the options that should be inherited from base configs (if not
# explicitly provided)
configInherit = ['runSpec', 'files', 'outputs', 'linux-src', 'linux-config', 'builder', 'distro']

# These are the permissible base-distributions to use (they get treated special)
distros = {
        'fedora' : fed.Builder(),
        'br' : br.Builder(),
        'bare' : bare.Builder()
        }

class RunSpec():
    def __init__(self, cfg):
        self.path = cfg.get('run')
        self.command = cfg.get('command')
        if self.path and self.command:
            raise ValueError("'command' and 'run' options are mutually exclusive")

FileSpec = collections.namedtuple('FileSpec', [ 'src', 'dst' ])

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
            cfgDir = os.path.dirname(self.cfg['cfg-file'])
        else:
            assert ('workdir' in self.cfg), "No workdir or cfg-file provided"
            assert ( os.path.isabs(self.cfg['workdir'])), "'workdir' must be absolute for hard-coded configurations (i.e. those without a config file)"

        # Some default values
        if 'workdir' in self.cfg:
            if not os.path.isabs(self.cfg['workdir']): 
                self.cfg['workdir'] = os.path.join(cfgDir, self.cfg['workdir'])
        else:
            self.cfg['workdir'] = os.path.join(cfgDir, self.cfg['name'])

        if 'linux-src' not in self.cfg:
            self.cfg['linux-src'] = linux_dir

        # Convert stuff to absolute paths (this should happen as early as
        # possible because the next steps all assume absolute paths)
        for k in (set(configToAbs) & set(self.cfg.keys())):
            if not os.path.isabs(self.cfg[k]):
                self.cfg[k] = os.path.join(self.cfg['workdir'], self.cfg[k])

        # Convert files to namedtuple and expand source paths to absolute (dest is already absolute to rootfs) 
        if 'files' in self.cfg:
            fList = []
            for f in self.cfg['files']:
                fList.append(FileSpec(src=os.path.join(self.cfg['workdir'], f[0]), dst=f[1]))

            self.cfg['files'] = fList

        # Distros are indexed by their name, not a path (since they don't have real configs)
        # All other bases should converted to absolute paths
        if 'base' in self.cfg:
            if self.cfg['base'] not in distros and not os.path.isabs(self.cfg['base']):
                self.cfg['base'] = os.path.join(cfgDir, self.cfg['base'])
        
        # This object handles setting up the 'run' and 'command' options
        if 'run' in self.cfg or 'command' in self.cfg:
            self.cfg['runSpec'] = RunSpec(self.cfg)

        # Convert overlay to file list (main program doesn't handle overlays directly)
        if 'overlay' in self.cfg:
            self.cfg.setdefault('files', [])
            files = glob.glob(os.path.join(self.cfg['overlay'], '*'))
            for f in files:
                self.cfg['files'].append(FileSpec(src=f, dst='/'))

        # Convert jobs to standalone configs
        if 'jobs' in self.cfg:
            jList = self.cfg['jobs']
            self.cfg['jobs'] = {}
            
            for jCfg in jList:
                jCfg['workdir'] = self.cfg['workdir']
                # jCfg['cfg-file'] = self.cfg['cfg-file']
                # TODO come up with a better scheme here, name is used to
                # derive the img and bin names, but naming jobs this way makes
                # for ugly hacks later when looking them up. 
                jCfg['name'] = self.cfg['name'] + '-' + jCfg['name']
                # jobs can base off any workload, but default to the current workload
                if 'base' not in jCfg:
                    jCfg['base'] = cfgFile

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
            self.cfg['img'] = os.path.join(image_dir, self.cfg['name'] + ".img")

        if 'bin' not in self.cfg:
            self.cfg['bin'] = os.path.join(image_dir, self.cfg['name'] + "-bin")

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
                for cfgFile in glob.iglob(os.path.join(d, "*.json")):
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
            try:
                log.debug("Loading " + f)
                self.cfgs[f] = Config(f)
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
        print("I RAN!")
        del self.cfgs[key]

    def __iter__(self):
        return iter(self.cfgs)

    def __len__(self):
        return len(self.cfgs)

    def __str__(self):
        return pprint.pformat(self.cfgs)

    def __repr__(self):
        return repr(self.cfgs)

