from time import sleep
import subprocess
import sys

operations_dict = {'and': ' & ',
                   'or': ' | ',
                   'xor': ' ^ '}


#query
#recouple
def in_file_split(input_file, delimiter, online=False, p=None):

    split_list = []
    if online:
        #if p.poll(1):
        #    line = input_file.stdout.readline().decode(sys.stdout.encoding)
        line = input_file.stdout.readline().decode(sys.stdout.encoding)

    else:
        line = input_file.readline()

    while delimiter not in line:
        split_list.append(line)
        if online:
            line = input_file.stdout.readline().decode(sys.stdout.encoding)
        else:
            line = input_file.readline()

    return "".join(split_list)


#query
def parse_operator(op_string):
    if op_string in operations_dict.keys():
        operator = operations_dict[op_string]
        return operator
    elif op_string[:6] == 'lambda':
        split_op_string = op_string.split()
        for i in range(len(split_op_string)):
            s = split_op_string[i]
            if s in operations_dict.keys():
                split_op_string[i] = operations_dict[s]
        return '(' + op_string + ')'
    else:
        return None


#query
def extract_hierarchy_information(vars):
    scoped_vars = []
    for var in vars:
        scoped_vars.append(var.split('.'))

    vars_dict = []
    for sv in scoped_vars:
        if len(sv) > 1:
            vars_dict.append((sv[-1], sv[:-1]))
        else:
            vars_dict.append((sv[0], ()))

    return vars_dict#, var_list


#helper
def check_scopes(scope_list, var_scope, vcs=False):
    exact_scoping = False
    #print(var_scope)
    if len(var_scope) == 0:
        exact_scoping = True
        if vcs:
            var_scope = ['emul']
        else:
            var_scope = ['TOP']

    index = 0
    #print(scope_list)
    #print(exact_scoping)
    #print(var_scope)
    for s in scope_list:
        if index >= len(var_scope):
            return not exact_scoping
        if s == var_scope[index]:
            index += 1
        elif index > 0:
            return False
    return True


#query
def extract_relevant_ids(definitions, vars, vcs=False):
    """
    :param definitions: String representing the variable descripotions in .vcd
    :param var_names: list of variables to extract in formation scope.subscope.var_name (can give more scope info)
    :return: dictionary of
    """

    # Just a list of tuples, not actual dictionary to support multiple items with the same dictionary
    vars_dict = extract_hierarchy_information(vars)
    vars_dict_keys = [e[0] for e in vars_dict]
    vars_dict_items = [e[1] for e in vars_dict]
    split_def = definitions.split("$end")
    #print(split_def[0])
    scope_list = []#['emul']
    id_dict = dict()
    for i in range(len(split_def)):
        #print(vars_dict_keys)
        s = split_def[i]
        parsed_def = [st for st in s.split() if st]
        if '$upscope' in s:
            #print(scope_list)
            scope_list.pop()
        elif '$scope' in s:
            curr_scope = parsed_def[-1]
            #print(curr_scope)
            scope_list.append(curr_scope)
        elif '$var' in s:
            var = parsed_def[4]
            if var not in vars_dict_keys:
                pass
            else:
                indices = []
                for i in range(len(vars_dict_keys)):
                    if var == vars_dict_keys[i]:
                        indices.append(i)
                for index in indices:
                    #print(check_scopes(scope_list, vars_dict_items[index]))
                    if check_scopes(scope_list, vars_dict_items[index], vcs):
                        var = ".".join(scope_list[:] + [var])
                        if var not in id_dict.keys():
                            id_dict[var] = parsed_def[3]
    #print(id_dict)
    if len(vars) != len(id_dict.keys()):
        return None
    return id_dict


#query
def all_same_id(id_dict):
    ids = [a[1] for a in id_dict.items()]
    for i in range(len(ids)):
        for j in range(len(ids)):
            if ids[i] != ids[j]:
                return False
    return True


#query
def operate_on_value_dump(id_dict, input_file, operator, time_range=None):
    id_list = [item[1] for item in id_dict.items()]
    value_dict = dict()
    accumulated_value_dict = dict()
    line = input_file.readline()
    prev_time = '0'
    time_lock = False
    while line:
        #print(repr(line))
        line = line[:-1] # gets rid of newline character
        if not line:
            pass
        elif line[0] == "#":
            time = line[1:]
            if time_range:
                if time_range[0] <= int(prev_time) <= time_range[1]:
                    time_lock = False
                    accumulated_value_dict[prev_time] = accumulate_values(value_dict, operator)
                if int(prev_time) < time_range[0]:
                    time_lock = True
                elif int(prev_time) > time_range[1]:
                    return accumulated_value_dict
            else:
                accumulated_value_dict[prev_time] = accumulate_values(value_dict, operator)
            prev_time = time
        elif time_lock:
            pass
        elif line[0] == "b":
            var_data, var_id = line.split()
            if var_id[:len(var_id)] in id_list:
                value_dict[var_id] = "'0{}'".format(var_data)
        else:
            var_id = line[1:]
            var_data = line[0]
            if var_id in id_list:
                value_dict[var_id] = "'{}'".format(var_data)
        line = input_file.readline()

    return accumulated_value_dict


#helper
def accumulate_values(value_dict, operator):
    acc_value = None
    value_list = [item[1] for item in value_dict.items()]
    #print(value_list[0])
    i = 0
    while i < len(value_list):
        #print(i)
        if 'x' in value_list[i]:
            return None  # invalid value in simulation
        if i == 0:
            if 'x' in value_list[1]:
                return None  # invalid value in simulation
            if operator[:7] == '(lambda':
                acc_value = eval(operator + '(int({}, 2), int({}, 2))'.format(value_list[0], value_list[1]))
            else:
                acc_value = eval('int({}, 2)'.format(str(value_list[0])) + operator + 'int({}, 2)'.format(str(value_list[1])))
            i += 2
        else:
            if operator[:7] == '(lambda':
                acc_value = eval(operator + '(int({}, 2), int({}, 2))'.format(acc_value, value_list[i]))
            else:
                acc_value = eval(str(acc_value) + operator + 'int({}, 2)'.format(str(value_list[i])))
            i += 1
    return acc_value


#query
def basic_assertion_analysis(assertion_data):
    first_assert = None
    last_assert = None
    num_assertions = 0

    for e in assertion_data.items():
        if e[1]:
            if first_assert is None:
                first_assert = e[0]
            last_assert = e[0]
            num_assertions += 1

    return first_assert, last_assert, num_assertions


#recouple
def remove_host_definitions(definitions, target='target', vcs=False):
    """
    :param definitions: String representing the first part of .vcd file describing waveform definitions
    :return: String representation of definitions only with only target and sim clock
    """
    # If target = None, provide definitions of all target modules

    split_def = definitions.split("$end")
    timescale_def = None
    for s in split_def:
        if "$timescale" in s:
            timescale_def = s + "$end\n"
            break

    #_, definitions = definitions.split("$scope module FPGATop $end")
    if vcs:
        _, definitions = definitions.split("$scope module emul $end")
    else:
        _, definitions = definitions.split("$scope module verilator_top $end")
    main_clock_id = definitions.split("$end")[0].split()[-2] #NOTE: requires the line directly after FPGATop to be the
                                                             #the host clock. This needs to be changed when firesim
                                                             #updates

    _, target_defs = definitions.split("$scope module {} $end".format(target))
    target_defs_split = target_defs.split("$end")
    scope_num = 1
    upscope_num = 0
    index = -1
    #narrows down target scope
    for i in range(len(target_defs_split)):
        s = target_defs_split[i]
        if "$scope" in s:
            scope_num += 1
        elif "$upscope" in s:
            upscope_num += 1
        if scope_num == upscope_num:
            index = i
            break

    target_defs_split = target_defs_split[:index]

    time_variable_definition = "$var wire      64 {}  time $end\n".format(main_clock_id)
    target_defs = "$scope module {} $end\n".format(target) + time_variable_definition + "$end".join(target_defs_split[:index]) + "$end\n"
    #print(print(target_defs))
    return timescale_def + target_defs, target_defs_split, main_clock_id


#recouple
def trim_and_write_value_dump(id_set, input_file, output_file, real_timing_id, target_clock_ids, online = False, p = None):
    current_time_values = []
    recoupled_time = 0
    original_time = 0

    if online:
        #if p.poll(1):
        #    line = input_file.stdout.readline().decode(sys.stdout.encoding)
        line = input_file.stdout.readline().decode(sys.stdout.encoding)

    else:
        line = input_file.readline()
    while line:
        if line:
            if line[0] == "#":
                original_time = line[1:]
            elif line[0] == "b":
                #_, var_id = line.split()
                var_id = line.split()[-1]
                if len(line.split()) == 1:
                    print('Error: non-blocking online read failed at time {} in recoupled vcd'.format(recoupled_time))
                    #print(original_time)
                    print(line.split())
                if var_id in id_set:
                    current_time_values.append(line)
            else:
                var_id = line[1:-1]
                if var_id in target_clock_ids:
                    if current_time_values:
                        output_file.write("#{}\n".format(recoupled_time))
                        output_file.write("".join(current_time_values) + "\n")
                        output_file.write(convert_to_binary_string(original_time, 64) + " {}\n".format(real_timing_id))
                        recoupled_time += 1
                        current_time_values = []
                if var_id in id_set:
                    current_time_values.append(line)
        if online:
            #if p.poll(1):
            #    line = input_file.stdout.readline().decode(sys.stdout.encoding)
            line = input_file.stdout.readline().decode(sys.stdout.encoding)

        else:
            line = input_file.readline()

    return 0


#helper
def convert_to_binary_string(num, bitsize):
    """
    :param num:
    :param bitsize:
    :return: binary number in "b00...." notation, in string format
    """
    num = int(num)
    binary_num_array = [str(0) for _ in range(bitsize)]
    index = 0
    while num >= 1 and index <= bitsize:
        index += 1
        binary_num_array[-index] = str(int(num % 2))
        num = num / 2

    return "b{}".format("".join(binary_num_array))


#recouple
def remove_newline(string):
    return string.splitlines()[-1]


# TEST FUNCTIONS


def copy_file(input_file, output_file):
    line = input_file.readline()
    while line:
        output_file.write(line)
        #sleep(0.001)
        line = input_file.readline()
