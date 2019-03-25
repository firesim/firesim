./bfs -f benchmark/graphs/twitter.sg -n64 > benchmark/out/bfs-twitter.out
./pr -f benchmark/graphs/twitter.sg -i1000 -t1e-4 -n16 > benchmark/out/pr-twitter.out
./cc -f benchmark/graphs/twitter.sg -n16 > benchmark/out/cc-twitter.out
./bc -f benchmark/graphs/twitter.sg -i4 -n16 > benchmark/out/bc-twitter.out
./bfs -f benchmark/graphs/web.sg -n64 > benchmark/out/bfs-web.out
./pr -f benchmark/graphs/web.sg -i1000 -t1e-4 -n16 > benchmark/out/pr-web.out
./cc -f benchmark/graphs/web.sg -n16 > benchmark/out/cc-web.out
./bc -f benchmark/graphs/web.sg -i4 -n16 > benchmark/out/bc-web.out
./bfs -f benchmark/graphs/road.sg -n64 > benchmark/out/bfs-road.out
./pr -f benchmark/graphs/road.sg -i1000 -t1e-4 -n16 > benchmark/out/pr-road.out
./cc -f benchmark/graphs/road.sg -n16 > benchmark/out/cc-road.out
./bc -f benchmark/graphs/road.sg -i4 -n16 > benchmark/out/bc-road.out
./bfs -f benchmark/graphs/kron.sg -n64 > benchmark/out/bfs-kron.out
./pr -f benchmark/graphs/kron.sg -i1000 -t1e-4 -n16 > benchmark/out/pr-kron.out
./cc -f benchmark/graphs/kron.sg -n16 > benchmark/out/cc-kron.out
./bc -f benchmark/graphs/kron.sg -i4 -n16 > benchmark/out/bc-kron.out
./tc -f benchmark/graphs/kronU.sg -n3 > benchmark/out/tc-kron.out
./bfs -f benchmark/graphs/urand.sg -n64 > benchmark/out/bfs-urand.out
./pr -f benchmark/graphs/urand.sg -i1000 -t1e-4 -n16 > benchmark/out/pr-urand.out
./cc -f benchmark/graphs/urand.sg -n16 > benchmark/out/cc-urand.out
./bc -f benchmark/graphs/urand.sg -i4 -n16 > benchmark/out/bc-urand.out
./tc -f benchmark/graphs/urandU.sg -n3 > benchmark/out/tc-urand.out
./sssp -f benchmark/graphs/twitter.wsg -n64 -d2 > benchmark/out/sssp-twitter.out
./sssp -f benchmark/graphs/web.wsg -n64 -d2 > benchmark/out/sssp-web.out
./sssp -f benchmark/graphs/road.wsg -n64 -d50000 > benchmark/out/sssp-road.out
./sssp -f benchmark/graphs/kron.wsg -n64 -d2 > benchmark/out/sssp-kron.out
./sssp -f benchmark/graphs/urand.wsg -n64 -d2 > benchmark/out/sssp-urand.out
./tc -f benchmark/graphs/twitterU.sg -n3 > benchmark/out/tc-twitter.out
./tc -f benchmark/graphs/webU.sg -n3 > benchmark/out/tc-web.out
./tc -f benchmark/graphs/roadU.sg -n3 > benchmark/out/tc-road.out
