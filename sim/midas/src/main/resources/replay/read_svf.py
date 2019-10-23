import sys
import argparse
# See LICENSE for license details.


def construct_instance_map(instance_map, tokens):
  is_design = False
  is_instance = False
  is_module = False
  for token in tokens:
    if token == "{" or token == "}":
      pass 
    elif token == "-design":
      is_design = True
    elif token == "-instance":
      is_instance = True
    elif token == "-linked":
      is_module = True
    elif is_design:
      design = token
      is_design = False
    elif is_instance:
      instance = token
      is_instance = False
    elif is_module:
      module = token
      is_module = False
    else:
      print token, ': ', ' '.join(tokens)
      assert False

  if not design in instance_map:
    instance_map[design] = dict()
  instance_map[design][instance] = module
  return

def uniquify_instances(instance_map, tokens):
  state = -1
  for token in tokens:
    if token == "{" or token == "}":
      pass 
    elif token == "-design":
      state = 0
    elif state == 0:
      """ identify a parent module name """
      top = token
      state = 1
    elif state == 1:
      """ identify an child instance name """
      design = top
      path_tokens = token.split("/")
      for path_token in path_tokens[:-1]:
        design = instance_map[design][path_token]
      instance = path_tokens[-1]
      path = '.'.join(path_tokens) # for debugging
      state = 2
    elif state == 2:
      """ identify a child module name """
      if not design in instance_map:
        instance_map[design] = dict()
      instance_map[design][instance] = token
      state = 1
    else:
      print token, ': ', ' '.join(tokens)
      assert False

  return

def construct_change_names(change_names, tokens):
  state = -1
  for token in tokens:
    if token == '{' or token == '}':
      pass
    elif token == "-design":
      state = 0
    elif state == 0:
      """ identify a parent module name """
      design = token
      state = 1
    elif state == 1:
      """ identify an object type """
      is_cell = token == "cell"
      state = 2
    elif state == 2:
      """ identify an rtl name """
      rtl_name = token
      state = 3
    elif state == 3:
      if not design in change_names:
        change_names[design] = dict()
      """ record name changes only for cells """
      if is_cell:
        change_names[design][token] = rtl_name
      state = 1
    else:
      print token, ': ', ' '.join(tokens)
      assert False

  return

def read_svf_file(svf_file):
  instance_map = dict()
  change_names = dict()

  with open(svf_file, 'r') as f:
    full_line = ""
    for line in f:
      tokens = line.split()
      if len(tokens) == 0:
        pass
      elif tokens[-1] == '\\':
        full_line += ' '.join(tokens[:-1]) + ' '
      else:
        full_line += ' '.join(tokens)
        full_tokens = full_line.split()
        guide_cmd = full_tokens[0]

        if guide_cmd == "guide_instance_map":
          construct_instance_map(instance_map, full_tokens[1:])
        elif guide_cmd == "guide_uniquify":
          uniquify_instances(instance_map, full_tokens[1:])
        elif guide_cmd == "guide_change_names":
          construct_change_names(change_names, full_tokens[1:])

        full_line = ""

  return instance_map, change_names

