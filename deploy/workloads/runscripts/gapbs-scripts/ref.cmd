./bfs -f benchmark/graphs/kron.sg -n64 > benchmark/out/bfs-kron.out
./pr -f benchmark/graphs/kron.sg -i1000 -t1e-4 -n16 > benchmark/out/pr-kron.out
./cc -f benchmark/graphs/kron.sg -n16 > benchmark/out/cc-kron.out
./bc -f benchmark/graphs/kron.sg -i4 -n16 > benchmark/out/bc-kron.out
./tc -f benchmark/graphs/kronU.sg -n3 > benchmark/out/tc-kron.out
./sssp -f benchmark/graphs/kron.wsg -n64 -d2 > benchmark/out/sssp-kron.out
