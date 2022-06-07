import os

RUN_LOCAL = not os.environ.get('GITHUB_ACTIONS', False)
local_fsim_dir = os.path.dirname(os.path.realpath(__file__)) + "/../.."
