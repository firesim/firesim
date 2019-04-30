import sys

assert(len(sys.argv) == 5)
src = open(sys.argv[1], "r")
tgt = open(sys.argv[2], "w+")
state = 0
tgt.write("circuit PicoRV32:\n")
for line in src:
	if "module " + sys.argv[4] in line and state == 0:
		state = 1
	elif "module" in line and state == 1:
		state = 0
	if state == 1:
		tgt.write(line)
src.close()
synth_src = open(sys.argv[3], "r")
state = 0
for line in synth_src:
	if "circuit" in line and state == 0:
		state = 1
	elif state == 1:
		tgt.write(line)
synth_src.close()
tgt.close()
