import re
import struct
import sys
from collections import namedtuple, defaultdict

FLITRE = re.compile(r"valid data chunk: ([0-9a-f]+), last ([01]), (send|recv)cycle: (\d+)")
RMEM_REQ_ETH_TYPE  = 0x0408
RMEM_RESP_ETH_TYPE = 0x0508
RMEM_OC_SPAN_READ  = 0x00
RMEM_OC_SPAN_WRITE = 0x01
RMEM_RC_SPAN_OK    = 0x80
RMEM_RC_NODATA_OK  = 0x81

Flit = namedtuple("Flit", ["data", "last", "sendrecv", "timestamp"])
FlitGroup = namedtuple("Flit", ["data", "sendrecv", "timestamp"])
RemoteMemRequest = namedtuple("RemoteMemRequest",
                        ["version", "opcode", "xactid", "spanid", "data",
                         "sendrecv", "timestamp"])
RemoteMemResponse = namedtuple("RemoteMemResponse",
                        ["version", "respcode", "xactid", "data",
                         "sendrecv", "timestamp"])
OtherPacket = namedtuple("OtherPacket", ["ethtype", "len", "sendrecv", "timestamp"])

def parse_flits(stream):
    for line in stream:
        match = FLITRE.search(line)
        if not match:
            continue
        datum, last, sendrecv, cycle = match.groups()
        yield Flit(int(datum, 16), last == '1', sendrecv == "send", int(cycle))

def group_flits(flits):
    send_data = []
    recv_data = []
    send_start = 0
    recv_start = 0

    for flit in flits:
        if flit.sendrecv:
            if len(send_data) == 0:
                send_start = flit.timestamp
            send_data.append(flit.data)
            if flit.last:
                yield FlitGroup(send_data, True, send_start)
                send_data = []
        else:
            if len(recv_data) == 0:
                recv_start = flit.timestamp
            recv_data.append(flit.data)
            if flit.last:
                yield FlitGroup(recv_data, False, recv_start)
                recv_data = []

    if send_data:
        print("Error: dangling packet data")
        print([hex(i) for i in send_data])

    if recv_data:
        print("Error: dangling packet data")
        print([hex(i) for i in recv_data])

def parse_packets(flitgroups):
    xactcount = defaultdict(int)
    for group in flitgroups:
        if len(group.data) > 2:
            ethtype = (group.data[1] >> 48) & 0xffff
            if ethtype == RMEM_REQ_ETH_TYPE:
                (version, opcode, xactid) = struct.unpack(
                        "<BBxxI", struct.pack("<q", group.data[2]))
                spanid = group.data[3]
                yield RemoteMemRequest(
                        version, opcode, xactid, spanid, group.data[4:],
                        group.sendrecv, group.timestamp)
            elif ethtype == RMEM_RESP_ETH_TYPE:
                (version, respcode, xactid) = struct.unpack(
                        "<BBxxI", struct.pack("<q", group.data[2]))
                yield RemoteMemResponse(
                        version, respcode, xactid, group.data[3:],
                        group.sendrecv, group.timestamp)
            else:
                yield OtherPacket(
                        ethtype, len(group.data) - 2,
                        group.sendrecv, group.timestamp)

def format_log(f):
    flits = parse_flits(f)
    groups = group_flits(flits)
    packets = parse_packets(groups)
    xactmatches = defaultdict(int)

    for pkt in packets:
        if isinstance(pkt, RemoteMemRequest):
            if pkt.opcode == RMEM_OC_SPAN_READ:
                print("{} read req spanid={} xactid={}".format(
                    pkt.timestamp, pkt.spanid, pkt.xactid))
            elif pkt.opcode == RMEM_OC_SPAN_WRITE:
                print("{} write req spanid={} xactid={}".format(
                    pkt.timestamp, pkt.spanid, pkt.xactid))
                for flit in pkt.data:
                    print("{:016x}".format(flit))
            xactmatches[pkt.xactid] += 1
        elif isinstance(pkt, RemoteMemResponse):
            if pkt.respcode == RMEM_RC_SPAN_OK:
                print("{} read resp xactid={}".format(
                    pkt.timestamp, pkt.xactid))
                for flit in pkt.data:
                    print("{:016x}".format(flit))
            elif pkt.respcode == RMEM_RC_NODATA_OK:
                print("{} write resp xactid={}".format(
                    pkt.timestamp, pkt.xactid))
            xactmatches[pkt.xactid] -= 1

    for xactid in xactmatches:
        if xactmatches[xactid] > 0:
            print("Unmatched xact id " + str(xactid))

def main():
    if len(sys.argv) < 2:
        format_log(sys.stdin)
    else:
        with open(sys.argv[1]) as f:
            format_log(f)

if __name__ == "__main__":
    main()
