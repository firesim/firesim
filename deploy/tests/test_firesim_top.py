import logging
import pytest
from textwrap import dedent
import re
import sure

import firesim
from firesim import register_task, FiresimTaskAccessViolation
from runtools.runtime_config import RuntimeConfig

rootLogger = logging.getLogger()

# In case you put any package-level tests, make sure they use the test credentials too
pytestmark = pytest.mark.usefixtures("aws_test_credentials")

def test_task_with_no_parameters(monkeypatch):
    monkeypatch.setattr(firesim, 'TASKS', {})

    def my_favorite_task():
        pass

    firesim.TASKS.should_not.contain('my_favorite_task')
    register_task(my_favorite_task)
    firesim.TASKS.should.contain('my_favorite_task')

def test_task_with_annotated_parameter(monkeypatch):
    monkeypatch.setattr(firesim, 'TASKS', {})

    def my_favorite_task(config: RuntimeConfig):
        pass

    firesim.TASKS.should_not.contain('my_favorite_task')
    register_task(my_favorite_task)
    firesim.TASKS.should.contain('my_favorite_task')


def task_missing_type_param(config):
    pass


class ParamlessXtor:
    def __init__(self):
        pass

def task_param_xtor_takes_no_param(config: ParamlessXtor):
    pass

class UnannotatedXtor:
    def __init__(self, config):
        pass

def task_param_xtor_unannotated(config: UnannotatedXtor):
    pass

class NotANamespace:
    pass

class XtorDoesNotTakeNamespace:
    def __init__(self, param: NotANamespace):
        pass

def task_param_xtor_not_namespace(config: XtorDoesNotTakeNamespace):
    pass


@pytest.mark.parametrize(
    'task,exception,regex',
    [
        (task_missing_type_param, TypeError, re.compile(r'requires type annotation on first parameter')),
        (task_param_xtor_takes_no_param, TypeError, re.compile(r'constructor takes no param')),
        (task_param_xtor_not_namespace, AssertionError, None),
        (task_param_xtor_unannotated, TypeError, re.compile(r'needs type annotation on.*first parameter')),
    ]
)
def test_task_with_annotated_parameter(monkeypatch, task, exception, regex):
    monkeypatch.setattr(firesim, 'TASKS', {})

    register_task.when.called_with(task).should.throw(exception, regex)
    firesim.TASKS.should.be.empty


class FirstReg:
    def duplicate_task(self, config: RuntimeConfig):
        pass

class SecondReg:
    def duplicate_task(self, config: RuntimeConfig):
        pass

def test_duplicate_registration(monkeypatch):
    monkeypatch.setattr(firesim, 'TASKS', {})

    # it is more likely that these functions would be in two different modules maybe but
    # for the purposes of testing, having them be in two different classes works too

    one = FirstReg()
    two = SecondReg()

    firesim.TASKS.should.be.empty
    register_task(one.duplicate_task)
    firesim.TASKS.should.contain('duplicate_task')
    assert False, firesim.TASKS['duplicate_task']
    register_task.when.called_with(two.duplicate_task).should.throw(KeyError, re.compile(r'Task.*already registered by'))


def test_decorated_task_callability():
    firesim.managerinit.when.called_with().should.throw(FiresimTaskAccessViolation)
