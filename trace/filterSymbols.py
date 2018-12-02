import sys

symbols = open(sys.argv[1])
symlist = open(sys.argv[2])
output = open(sys.argv[3], "w")

slist = []

for line in symlist:
	slist.append(line.strip())

for line in symbols:
	elems = line.split(",")
	if(elems[2] == "linux" or elems[2] == "sm"):
		if(elems[3].strip() in slist):
			output.write(line)
	else:
		output.write(line)
