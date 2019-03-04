#! /bin/bash
echo "Running the GAPBS subsuite."
cd /gabps/ && ./gapbs.sh bfs-kron --counters
cd /gabps/ && ./gapbs.sh sssp-kron --counters
cd /gabps/ && ./gapbs.sh cc-kron --counters
