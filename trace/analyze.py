import sys

traceFileName = sys.argv[1]
symbolFileName = sys.argv[2]
outputFileName = sys.argv[3]
traceFile = open(traceFileName)
symbolFile = open(symbolFileName)
outputFile = open(outputFileName, "w")

map = {}

for line in symbolFile:
	elems = line.split(",")
	addr = int(elems[0], 16) & 0xffffffffff
	try:
		size = int(elems[1])
	except:
		continue
	type = elems[2]
	name = elems[3]
	map[addr] = (size, type, name)

last_was_user = 0
for line in traceFile:
	elems = line.split(",")
	try:
		int(elems[0])
	except:
		continue

	idx = int(elems[0])
	pc = int(elems[1], 16) & 0xffffffffff

	if ( pc <= 0xf000 and last_was_user == 0):
		last_was_user = 1
		outputFile.write(str(idx)+",user,unknown\n")
		continue
	else:
		last_was_user = 0

	try:
		outputFile.write(str(idx)+","+map[pc][1]+","+map[pc][2] )
		outputFile.flush()
	except:
		continue
