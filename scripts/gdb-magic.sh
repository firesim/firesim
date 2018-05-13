
# this is an easy way to get a rough sense for what is going on in a FireSim
# simulation. It will look for all the drivers, attach to them with gdb,
# print the backtrace, and quit, letting the processes go on and continue
#
#
# for example, to see what /isn't/ blocking polling on tokens from the switch,
# do:
#
# sudo bash gdb-magic.sh | grep simplenic | wc -l

pids=($(pgrep SupernodeTop))

echo -e "bt\nquit" > rungdb


for i in "${pids[@]}"
do
    echo $i
    sudo gdb -p $i -x rungdb
done

