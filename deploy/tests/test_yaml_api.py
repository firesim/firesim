from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
import logging
from os import fspath
from pathlib import Path
import pytest
import re
try:
    # shlex.join is added in 3.8
    from shlex import join as shjoin
except ImportError:
    def shjoin(iterable):
        return ' '.join([shlex.quote(_) for _ in iterable])

import sure
from textwrap import dedent
import yaml

import firesim
from buildtools.buildconfigfile import BuildConfigFile
from runtools.runtime_config import RuntimeConfig


from typing import TYPE_CHECKING
if TYPE_CHECKING:
    from _yaml import _ReadStream


firesim.rootLogger = logging.getLogger()

# In case you put any package-level tests, make sure they use the test credentials too
pytestmark = pytest.mark.usefixtures("aws_test_credentials")


class TmpYaml:
    """Encapsulate our pattern for using sample-backup-configs"""
    def __init__(self, tmp_dir: Path, sample_config: Path) -> None:
        """
        Args:
            tmp_dir: path to temporary directory
            sample_config: path to the sample config that is
                           used to initialize our data
        """
        config_name = sample_config.name

        (tmp_name, nsubs) = re.subn(r'^sample_', 'test_', config_name)
        nsubs.should.equal(1)

        self.path = tmp_dir / tmp_name
        with sample_config.open('r') as f:
            self.load(f)

    def load(self, stream:'_ReadStream') -> None:
        self.data = yaml.safe_load(stream)

    def dump(self) -> str:
        return yaml.dump(self.data)

    def write(self) -> None:
        self.path.write_text(self.dump())

@dataclass
class TmpYamlSet(ABC):
    """Aggregate Fixture Encapsulating group of configs that get populated by the sample-backup-configs
       by default and can be manipulated either via YAML api or clobbering them
       with a string

       each 'TestConfig' has a:
         * path: pathlib.Path to a tempfile location where the config will be written
         * data: python datastructure that is set via yaml.safe_load

       Methods:

    """
    @abstractmethod
    def write(self) -> None:
        """Iterates the TmpYaml members calling their dump"""
        pass

    @property
    @abstractmethod
    def args(self) -> list:
        """Returns list of cmdline options needed for firesim argparser"""
        pass

    @property
    @abstractmethod
    def cmdline(self) -> str:
        """Returns string of cmdline options needed for firesim argparser"""
        pass

@dataclass
class BuildTmpYamlSet(TmpYamlSet):
    """Concrete TmpYamlSet for build configs

    Attributes:
    """
    build: TmpYaml
    recipes: TmpYaml
    hwdb: TmpYaml
    non_existent_file: Path

    def write(self):
        self.build.write()
        self.recipes.write()
        self.hwdb.write()

    @property
    def args(self):
        # set configs that should not be read explicitly
        # to the non_existent_file to avoid influence of
        # whether `firesim managerinit` has been run
        # https://github.com/firesim/firesim/pull/1145#issuecomment-1194392085
        return ['-b', fspath(self.build.path),
                '-r', fspath(self.recipes.path),
                '-a', fspath(self.hwdb.path),
                '-c', fspath(self.non_existent_file)
                ]

    @property
    def cmdline(self):
        return shjoin(self.args)

@dataclass
class RunTmpYamlSet(TmpYamlSet):
    """Concrete TmpYamlSet for run configs

    Attributes:
    """
    recipes: TmpYaml
    hwdb: TmpYaml
    run: TmpYaml
    non_existent_file: Path

    def write(self):
        self.recipes.write()
        self.hwdb.write()
        self.run.write()

    @property
    def args(self):
        # set configs that should not be read explicitly
        # to the non_existent_file to avoid influence of
        # whether `firesim managerinit` has been run
        # https://github.com/firesim/firesim/pull/1145#issuecomment-1194392085
        return ['-b', fspath(self.non_existent_file),
                '-r', fspath(self.recipes.path),
                '-a', fspath(self.hwdb.path),
                '-c', fspath(self.run.path)]

    @property
    def cmdline(self):
        return shjoin(self.args)

@pytest.fixture()
def sample_backup_configs() -> Path:
    dir = Path(__file__).parent.parent / 'sample-backup-configs'
    dir.is_dir().should.equal(True)
    return dir

@pytest.fixture()
def non_existent_file(tmp_path: Path) -> Path:
    # tmp_path is builtin pytest fixture to get a per-test temporary directory that should be clean
    # but we still make sure that it doesn't exist before giving it
    file = tmp_path / 'GHOST_FILE'
    file.exists().should.equal(False)
    return file

@pytest.fixture()
def scy_build(tmp_path: Path, sample_backup_configs: Path) -> TmpYaml:
    return TmpYaml(tmp_path, sample_backup_configs / 'sample_config_build.yaml')

@pytest.fixture()
def scy_build_recipes(tmp_path: Path, sample_backup_configs: Path) -> TmpYaml:
    return TmpYaml(tmp_path, sample_backup_configs / 'sample_config_build_recipes.yaml')

@pytest.fixture()
def build_yamls(scy_build: TmpYaml, scy_build_recipes: TmpYaml, scy_hwdb: TmpYaml, non_existent_file: Path) -> BuildTmpYamlSet:
    return BuildTmpYamlSet(scy_build, scy_build_recipes, scy_hwdb, non_existent_file)

@pytest.fixture()
def scy_hwdb(tmp_path: Path, sample_backup_configs: Path) -> TmpYaml:
    return TmpYaml(tmp_path, sample_backup_configs / 'sample_config_hwdb.yaml')

@pytest.fixture()
def scy_runtime(tmp_path: Path, sample_backup_configs: Path) -> TmpYaml:
    return TmpYaml(tmp_path, sample_backup_configs / 'sample_config_runtime.yaml')

@pytest.fixture()
def run_yamls(scy_build_recipes: TmpYaml, scy_hwdb: TmpYaml, scy_runtime: TmpYaml, non_existent_file: Path) -> RunTmpYamlSet:
    return RunTmpYamlSet(scy_build_recipes, scy_hwdb, scy_runtime, non_existent_file)

@pytest.fixture()
def firesim_parse_args():
    return lambda x: firesim.construct_firesim_argparser().parse_args(x)

@pytest.mark.usefixtures("aws_test_credentials")
class TestConfigBuildAPI:
    """ Test config_{build, build_recipes}.yaml APIs """

    def test_invalid_build_section(self, task_mocker, build_yamls, firesim_parse_args):
        m = task_mocker.patch('buildbitstream', wrap_config=True)

        # at the beginning of the test build_yamls contains the backup-sample-configs
        # but we can show exactly what we're doing different from the default by
        build_yamls.build.load(dedent("""
            build_farm:
              base_recipe: build-farm-recipes/aws_ec2.yaml

            builds_to_run:

            agfis_to_share:
                - testing_recipe_name

            share_with_accounts:
                INVALID_NAME: 123456789012
            """))

        build_yamls.write()
        args = firesim_parse_args(['buildbitstream'] + build_yamls.args)
        firesim.main.when.called_with(args).should.throw(TypeError)
        m['task'].assert_not_called()
        m['config'].assert_called_once_with(args)

    def test_invalid_buildfarm_recipe(self, task_mocker, build_yamls, firesim_parse_args):
        m = task_mocker.patch('buildbitstream', wrap_config=True)

        build_yamls.build.load(dedent("""
            build_farm:
              base_recipe: INVALID_RECIPE

            builds_to_run:
                - testing_recipe_name

            agfis_to_share:
                - testing_recipe_name

            share_with_accounts:
                INVALID_NAME: 123456789012
            """))
        build_yamls.recipes.load(dedent("""
            testing_recipe_name:
                DESIGN: TopModule
                TARGET_CONFIG: Config
                deploy_triplet: null
                PLATFORM_CONFIG: Config
                platform_config_args:
                    fpga_frequency: 123
                    build_strategy: TIMING
                post_build_hook: null
                bit_builder_recipe: bit-builder-recipes/f1.yaml
            """))
        build_yamls.write()
        args = firesim_parse_args(['buildbitstream'] + build_yamls.args)
        firesim.main.when.called_with(args).should.throw(FileNotFoundError, re.compile(r'INVALID_RECIPE'))
        # the exception should happen while building the config, before the task is actually called
        m['config'].assert_called_once_with(args)
        m['task'].assert_not_called()

    @pytest.mark.parametrize('task_name', [tn for tn in firesim.TASKS if
                                           firesim.TASKS[tn]['config'] is BuildConfigFile])
    @pytest.mark.parametrize('opt', ['-b',
                                     '-r',
                                    ])
    def test_config_existence(self, task_mocker, build_yamls, firesim_parse_args, task_name, opt, non_existent_file):
        # TODO: Remove after deprecation
        if task_name == "buildbitstream":
            task_name = "buildbitstream"

        m = task_mocker.patch(task_name, wrap_config=True)

        build_yamls.write()
        args = firesim_parse_args([task_name] + build_yamls.args + [opt, fspath(non_existent_file)])
        firesim.main.when.called_with(args).should.throw(FileNotFoundError, re.compile(r'GHOST_FILE'))
        m['task'].assert_not_called()
        m['config'].assert_called_once_with(args)

@pytest.mark.usefixtures("aws_test_credentials")
class TestConfigRunAPI:
    """ Test config_{runtime, hwdb}.yaml APIs """

    def test_invalid_default_hw_config(self, task_mocker, run_yamls, firesim_parse_args, sample_backup_configs, monkeypatch):
        task_name = 'runcheck'
        m = task_mocker.patch(task_name, wrap_config=True)
        #TODO the hardcoded relative paths to workload JSON make it challenging
        #to give each unit test it's own sandbox.  Need to think about this more.
        monkeypatch.chdir(sample_backup_configs.parent)


        run_yamls.run.data['target_config'].should.contain('default_hw_config')
        run_yamls.run.data['target_config']['default_hw_config'] = 'INVALID_CONFIG'
        run_yamls.run.data['run_farm']['recipe_arg_overrides'] = {"run_farm_hosts_to_use" : [ {"f1.16xlarge" : 1} ] }

        run_yamls.hwdb.data.should_not.contain('INVALID_CONFIG')

        run_yamls.write()
        args = firesim_parse_args([task_name] + run_yamls.args)
        firesim.main.when.called_with(args).should.throw(KeyError, re.compile(r'INVALID_CONFIG'))
        m['task'].assert_not_called()
        m['config'].assert_called_once_with(args)

    def test_invalid_topology(self, task_mocker, run_yamls, firesim_parse_args, sample_backup_configs, monkeypatch):
        task_name = 'runcheck'
        m = task_mocker.patch(task_name, wrap_config=True)
        #TODO the hardcoded relative paths to workload JSON make it challenging
        #to give each unit test it's own sandbox.  Need to think about this more.
        monkeypatch.chdir(sample_backup_configs.parent)


        run_yamls.run.data['target_config'].should.contain('topology')
        run_yamls.run.data['target_config']['topology'] = 'INVALID_TOPOLOGY'

        run_yamls.write()
        args = firesim_parse_args([task_name] + run_yamls.args)
        firesim.main.when.called_with(args).should.throw(AttributeError, re.compile(r'INVALID_TOPOLOGY'))
        m['task'].assert_not_called()
        m['config'].assert_called_once_with(args)

    def test_invalid_workloadname(self, task_mocker, run_yamls, firesim_parse_args, sample_backup_configs, monkeypatch):
        task_name = 'runcheck'
        m = task_mocker.patch(task_name, wrap_config=True)
        #TODO the hardcoded relative paths to workload JSON make it challenging
        #to give each unit test it's own sandbox.  Need to think about this more.
        monkeypatch.chdir(sample_backup_configs.parent)

        run_yamls.run.data['workload'].should.contain('workload_name')
        run_yamls.run.data['workload']['workload_name'] = 'INVALID_WORKLOAD'

        run_yamls.write()
        args = firesim_parse_args([task_name] + run_yamls.args)
        firesim.main.when.called_with(args).should.throw(FileNotFoundError, re.compile(r'INVALID_WORKLOAD'))
        m['task'].assert_not_called()
        m['config'].assert_called_once_with(args)


    @pytest.mark.parametrize('task_name', [tn for tn in firesim.TASKS if
                                           firesim.TASKS[tn]['config'] is RuntimeConfig])
    @pytest.mark.parametrize('opt',
                             ['-a',
                              '-c',
                              ])
    def test_config_existence(self, task_mocker, run_yamls, firesim_parse_args, task_name, opt, non_existent_file):
        m = task_mocker.patch(task_name, wrap_config=True)

        run_yamls.write()
        args = firesim_parse_args([task_name] + run_yamls.args + [opt, fspath(non_existent_file)])
        firesim.main.when.called_with(args).should.throw(FileNotFoundError, re.compile(r'GHOST_FILE'))
        m['task'].assert_not_called()
        m['config'].assert_called_once_with(args)
