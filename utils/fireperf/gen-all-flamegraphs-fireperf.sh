
# $1 will be the directory

RDIR=$(pwd)

cd $1
FILESITER=$(ls */TRACEFILE*)
echo $FILESITER

cd $RDIR

for i in $FILESITER
do
    echo $i
    flamegraph-tracerv $1/$i $1/$i.svg &
done

wait

exit

