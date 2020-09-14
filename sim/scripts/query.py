import argparse
from vcd_utils import *


def main():

    query_parser = argparse.ArgumentParser(description="Queries vcd files given variables to query")
    query_parser.add_argument('input_filename', help='Provides input filename of decoupled '
                                                     'waveform from firesim execution')
    query_parser.add_argument('var_names', nargs='+', action='extend',
                              help='Provides names of variables to be queried from VCD.\n'
                                   'Can provide hierarchy information as follows:\n'
                                   'target.clockBridge_clocks_0_buffer.O\n'
                                   'If no information provided, variables will be chosen as first variables with\n'
                                   'matching variable name in scope target')
    query_parser.add_argument('-o', '--operator', default='and',
                              help="Provides operator to be mapped across the variables "
                                   "provided by var_names. Currently must be an "
                                   "infix operator from the following supported list.\n"
                                   "Supported Operations: and : bitwise and,"
                                   "or : bitwise or, xor : bitwise xor.\n"
                                   "example usage: '-o xor' signifies bitwise xor usage")
    query_parser.add_argument('-l', '--lambda-operator', default=None, action='extend', nargs='*',
                              help="Alternative operator definition using lambdas instead of infix which allows more "
                                   "complex accumulation. Ex: 'lambda x,y: x and y'. Operators converted to bitwise as in -o"
                                   "usage. Also overrides -o.")
    # Cares about when signals are asserted
    # how fire function works -> let's you exclude and include signals -> can define all signals which have to be high
    # decoupled helper in rocketchip
    query_parser.add_argument('-t', '--time_range', nargs=2, default=None,
                              help='Provides time range operated on, inclusive'
                                   'of both bounds')
    query_parser.add_argument('-s', '--synopsis', action='store_true', help='Use this flag if using VCD instead of '
                                                                               'verilator')

    args = query_parser.parse_args()
    input_filename = args.input_filename
    if input_filename[-4:] != ".vcd":
        input_filename = args.input_filename + ".vcd"
    else:
        input_filename = args.input_filename

    time_range = None
    if args.time_range:
        time_range = [int(s) for s in args.time_range]

    input_wave_file = open(input_filename, "r")
    if args.lambda_operator is None:
        operator = parse_operator(args.operator)
    else:
        operator = parse_operator(" ".join(args.lambda_operator))

    assert operator is not None, "Invalid operator, run 'python query_vcd.py -h' for more info"

    definitions = in_file_split(input_wave_file, "$enddefinitions")

    # if args.target_module is not None:
    #    _, definitions, _ = remove_host_definitions(definitions, args.target_module)
    #    definitions = "$end".join(definitions)
    if args.synopsis:
        _, definitions = definitions.split('$scope module emul $end')
        definitions = '$scope module emul $end\n' + definitions
    else:
        _, definitions = definitions.split('$scope module TOP $end')
        definitions = '$scope module TOP $end\n' + definitions

    id_dict = extract_relevant_ids(definitions, args.var_names, args.synopsis)
    if id_dict is None:
        print(args.var_names)
        #print(id_dict.keys())
        print("Could not find all variables. Make sure that the hierarchy information is correct.")
        return
    if all_same_id(id_dict):
        print("All provided variables have the same id!!")
        return

    if args.synopsis:
        line = input_wave_file.readline()
        while "$dumpvars" not in line:
            line = input_wave_file.readline()

    assertion_dict = operate_on_value_dump(id_dict, input_wave_file, operator, time_range)
    assertion_data = basic_assertion_analysis(assertion_dict)

    #print(assertion_dict)
    print(id_dict)
    print("Following data shows time of first/last assertion, not cycles!")
    print("Positive clock edge aligned.")
    print("Earliest Assertion:   {}".format(assertion_data[0]))
    print("Latest Assertion:     {}".format(assertion_data[1]))
    print("Number of Assertions: {}".format(assertion_data[2]))
    input_wave_file.close()


if __name__ == "__main__":
    main()
