#!/usr/bin/env python3

from argparse import ArgumentParser
import json
import sys
import os
import copy
import subprocess
import signal
import time
import shlex
from datetime import datetime
from multiprocessing import Process, Queue


def mergeDict(destination, source):
    for key, value in source.items():
        if isinstance(value, dict):
            node = destination.setdefault(key, {})
            mergeDict(node, value)
        else:
            destination[key] = value
    return destination


def replaceValues(target, valueMap: dict):
    if not isinstance(target, str) and not isinstance(target, dict) and not isinstance(target, list):
        return target
    for k, v in valueMap.items():
        if isinstance(target, str):
            target = target.replace(k, v)
        elif isinstance(target, list):
            target = [replaceValues(x, valueMap) for x in target]
        elif isinstance(target, dict):
            target = {tK: replaceValues(tV, valueMap) for tK, tV in target.items()}
    return target


def runWorker(workerId, runDir, workloadQueue: Queue, resource):
    signal.signal(signal.SIGINT, signal.default_int_handler)
    signal.signal(signal.SIGTERM, signal.SIG_DFL)
    print(f'[Worker #{workerId}] worker started')
    if resource is not None:
        print(f"[Worker #{workerId}] exclusive resource '{resource}' assigned")

    workerValueMap = {
        '%worker%': str(workerId),
        '%resource%': '' if resource is None else resource,
        '%name%': ''
    }

    while True:
        try:
            workload = workloadQueue.get(False)
        except Exception:
            break

        print(f'[Worker #{workerId}] retrieved workload \'{workload["name"]}\'')
        workerValueMap['%name%'] = workload['name']
        sanWorkloadName = ''.join(x for x in workload['name'].replace(' ', '_') if x.isalnum() or x in '._-')
        workloadDir = None
        retries = 60
        print(f'[Worker #{workerId}] setting up workload environment')
        while workloadDir is None and retries > 0:
            try:
                workloadDir = runDir + '/' + datetime.now().strftime('%Y-%m-%d_%H-%M-%S') + '_' + sanWorkloadName
                os.makedirs(workloadDir)
            except Exception:
                workloadDir = None
                retries -= 1
                time.sleep(1)

        if workloadDir is None and retries <= 0:
            workloadQueue.put(workload)
            print(f"[Worker #{workerId}] failed to create workload dir for workload '{workload['name']}', exiting!")
            return

        try:
            logFile = open(workloadDir + '/run.log', 'w')
            print(f"[Worker #{workerId}] logging to '{workloadDir}/run.log'")
        except Exception:
            print(f"[Worker #{workerId}] failed to create log file '{workloadDir}/run.log', using stdout!")
            logFile = sys.stdout

        workloadEnvironment = replaceValues(mergeDict(os.environ.copy(), workload['environment']), workerValueMap)
        errorOccured = False
        for script in ['preexec', 'exec', 'postexec']:
            if workload['scripts'][script] is not None:
                workloadScriptCmd = [workload['scripts'][script]] + ([] if workload['parameters'][script] is None else replaceValues(shlex.split(workload['parameters'][script]), workerValueMap))
                print(f"[Worker #{workerId}] running {script} command '{' '.join(workloadScriptCmd)}'", file=logFile, flush=True)
                runWorkloadScript = subprocess.run(workloadScriptCmd, stdout=logFile, stderr=logFile, env=workloadEnvironment, cwd=workloadDir)
                if runWorkloadScript.returncode != 0:
                    errorOccured = True
                    print(f"[Worker #{workerId}] {script} returned with an error", file=logFile, flush=True)

        if errorOccured:
            print(f'[Worker #{workerId}] workload \'{workload["name"]}\' returned at least one error!')
        print(f'[Worker #{workerId}] workload \'{workload["name"]}\' finished executing!')

        if logFile != sys.stdout:
            logFile.close()

    print(f'[Worker #{workerId}]: done, exiting')


parser = ArgumentParser(description="Statically schedules workloads across any number of FPGA parallel")
parser.add_argument("config", help="schedule configuration", default=None)
parser.add_argument("-w", "--workloads", default=[], nargs='+', required=True, help="workload definitions")
parser.add_argument("--reverse", default=False, action="store_true", help="reverse workloads")
parser.add_argument("-p", "--parallel", default=None, type=int, help="number of parallel parallel threads (default number of exclusive resources or one)")
parser.add_argument("-r", "--resources", default=None, type=str, nargs='*', help="exclusive resources, each parallel getting one")
args = parser.parse_args()

if not os.path.exists(args.config):
    raise Exception(f'Could not find {args.config}')

if args.parallel is not None and args.parallel <= 0:
    raise Exception('Invalid instance number!')

for w in args.workloads:
    if not os.path.exists(w):
        raise Exception(f'Could not find {w}')

configTemplate = {
    'resources': {
        'acquire': None,
        'release': None
    },
    'default_workload': {
        'name': 'unnamed',
        'scripts': {
            'preexec': None,
            'exec': None,
            'postexec': None
        },
        'parameters': {
            'preexec': None,
            'exec': None,
            'postexec': None
        },
        'environment': {}
    },
    'run_dir': os.path.abspath('.'),
    'workloads': []
}

try:
    mainConfig = mergeDict(configTemplate, json.load(open(args.config, 'r')))
except Exception:
    raise Exception(f'Could not decode json file {args.config}')

if mainConfig['resources']['acquire'] is not None:
    if not os.path.isfile(mainConfig['resources']['acquire']):
        raise Exception(f"Acquire resource script '{mainConfig['resources']['acquire']}' not found")
    mainConfig['resources']['acquire'] = os.path.abspath(mainConfig['resources']['acquire'])

if mainConfig['resources']['release'] is not None:
    if not os.path.isfile(mainConfig['resources']['release']):
        raise Exception(f"Release resource script '{mainConfig['resources']['release']}' not found")
    mainConfig['resources']['release'] = os.path.abspath(mainConfig['resources']['release'])

mainConfig['run_dir'] = os.path.abspath(mainConfig['run_dir'])

for wFile in args.workloads:
    try:
        workloads = json.load(open(wFile, 'r'))
    except Exception:
        raise Exception(f'Could not decode json file {wFile}')
    if not isinstance(workloads, list) or not all(isinstance(w, dict) for w in workloads):
        raise Exception(f'Misformed workload defintion in file {wFile}')

    workloads = [mergeDict(copy.deepcopy(mainConfig['default_workload']), w) for w in workloads]
    for w in workloads:
        checkDicts = [w['scripts'], w['parameters'], w['environment']]
        if not isinstance(w['name'], str) or not all(isinstance(x, dict) for x in checkDicts) or not all([(isinstance(k, str) and (v is None or isinstance(v, str))) for cD in checkDicts for (k, v) in cD.items()]):
            raise Exception(f'Misformed workload defintion {w["name"]} in file {wFile}')

        for key in w['scripts'].keys():
            if w['scripts'][key] is not None:
                if not os.path.isfile(w['scripts'][key]):
                    raise Exception(f'Could not find script {w["scripts"][key]} in workload {w["name"]}')
                w['scripts'][key] = os.path.abspath(w['scripts'][key])

        if all(w['scripts'][k] is None for k in w['scripts'].keys()):
            raise Exception(f'Workload {w["name"]} has no scripts defined!')

    mainConfig['workloads'].extend(workloads)

if len(mainConfig['workloads']) == 0:
    raise Exception('No workloads found!', file=sys.stderr)

if args.reverse:
    mainConfig['workloads'].reverse()

print(f'Found {len(mainConfig["workloads"])} workloads')

dynResources = []

if args.parallel is not None:
    if args.parallel > len(mainConfig['workloads']):
        print('INFO: reducing number of workers to number of workloads')
        args.parallel = len(mainConfig['workloads'])
    if args.resources is not None and len(args.resources) > len(args.parallel):
        print('INFO: Too many resources provided for the number of workers')
        args.resources = args.resources[:args.parallel]

if mainConfig['resources']['acquire'] is not None:
    acquireDynResources = (args.parallel if args.parallel is not None else len(mainConfig['workloads'])) - (0 if args.resources is None else len(args.resources))
    if acquireDynResources > 0:
        print(f'Try to acquire {acquireDynResources} more resources...')
        run = subprocess.run([mainConfig['resources']['acquire'], str(acquireDynResources)], stdout=subprocess.PIPE)
        if run.returncode == 0:
            dynResources = [r.strip() for r in run.stdout.decode('utf-8').split('\n') if len(r.strip()) > 0]
            if mainConfig['resources']['release'] is not None and len(dynResources) > acquireDynResources:
                print('Too many resources acquired, releasing some!')
                run = subprocess.run([mainConfig['resources']['release']] + dynResources[acquireDynResources:], stdout=subprocess.PIPE)
                if run.returncode != 0:
                    print('Error during resource release!')
                dynResources = dynResources[:acquireDynResources]
            if len(dynResources) > 0:
                print(f'Acquired {len(dynResources)} more resources!')
        else:
            print('Error during resource acquirement!')

        if len(dynResources) == 0 and args.resources is None:
            raise('Could not acquire any resources to launch workloads!')
        else:
            args.resources = ([] if args.resources is None else args.resources) + dynResources


def cleanup(sig, frame):
    if mainConfig['resources']['release'] is not None and len(dynResources) > 0:
        print('Release acquired resources!')
        run = subprocess.run([mainConfig['resources']['release']] + dynResources, stdout=subprocess.PIPE)
        if run.returncode != 0:
            print('Error during resource release!')
    exit(0 if sig is None and frame is None else 1)


signal.signal(signal.SIGINT, cleanup)
signal.signal(signal.SIGTERM, cleanup)

if args.parallel is None:
    args.parallel = 1 if args.resources is None else len(args.resources)

if args.resources is None:
    args.resources = [None] * args.parallel

workloadQueue = Queue()
for w in mainConfig["workloads"]:
    workloadQueue.put(w)

if not os.path.exists(mainConfig['run_dir']):
    os.makedirs(mainConfig['run_dir'])

workers = []
print(f'Starting {args.parallel} workers')
for i in range(args.parallel):
    w = Process(target=runWorker, args=(i, mainConfig['run_dir'], workloadQueue, args.resources[i]))
    w.start()
    workers.append(w)

for w in workers:
    w.join()

if not workloadQueue.empty():
    print('WARNING: worker most likely encountered an error as workload queue is not empty, but workers have finished!')

print('All workers have finished!')
cleanup(None, None)
