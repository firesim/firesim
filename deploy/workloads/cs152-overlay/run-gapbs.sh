#! /bin/bash
echo "Running the GAPBS subsuite."
cd /gapbs/ && ./gapbs.sh bfs-kron --counters
cd /gapbs/ && ./gapbs.sh sssp-kron --counters
cd /gapbs/ && ./gapbs.sh cc-kron --counters
