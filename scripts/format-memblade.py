import re
import struct
import sys
from collections import namedtuple

FLITRE = re.compile(r"valid data chunk: ([0-9a-f]+), last ([01]), (send|recv)cycle: (\d+)")
RMEM_REQ_ETH_TYPE  = 0x0408
RMEM_RESP_ETH_TYPE = 0x0508
RMEM_OC_SPAN_READ  = 0x00
RMEM_OC_SPAN_WRITE = 0x01
RMEM_RC_SPAN_OK    = 0x80
RMEM_RC_NODATA_OK  = 0x81
SPAN_BYTES=1024
SPAN_FLITS=SPAN_BYTES/8

Flit = namedtuple("Flit", ["data", "last", "sendrecv", "timestamp"])
FlitGroup = namedtuple("Flit", ["data", "sendrecv", "timestamp"])
CreditUpdate = namedtuple("CreditUpdate", ["count", "timestamp"])
RemoteMemRequest = namedtuple("RemoteMemRequest",
                        ["version", "opcode", "xactid", "spanid", "timestamp"])
RemoteMemResponse = namedtuple("RemoteMemResponse",
                        ["version", "respcode", "xactid", "timestamp"])

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

def check_update(data):
    if len(data) != 1:
        print("Error: credit update packet too long")

def check_request(opcode, data):
    if opcode == RMEM_OC_SPAN_READ and len(data) != 4:
        print("Error: Span read request has wrong length")
    if opcode == RMEM_OC_SPAN_WRITE and len(data) != (4 + SPAN_FLITS):
        print("Error: Span write request has wrong length")

def check_response(opcode, data):
    if opcode == RMEM_RC_SPAN_OK and len(data) != (3 + SPAN_FLITS):
        print("Error: Span read response has wrong length")
    if opcode == RMEM_RC_NODATA_OK and len(data) != 3:
        print("Error: Span write response has wrong length")

def parse_packets(flitgroups):
    for group in flitgroups:
        if (group.data[0] & 0xffff) != 0:
            check_update(group.data)
            yield CreditUpdate(group.data[0] & 0xffff, group.timestamp)
        elif len(group.data) > 2:
            ethtype = (group.data[1] >> 48) & 0xffff
            if ethtype == RMEM_REQ_ETH_TYPE:
                (version, opcode, xactid) = struct.unpack(
                        "<BBxxI", struct.pack("<q", group.data[2]))
                spanid = group.data[3]
                check_request(opcode, group.data)
                yield RemoteMemRequest(
                        version, opcode, xactid, spanid, group.timestamp)
            elif ethtype == RMEM_RESP_ETH_TYPE:
                (version, respcode, xactid) = struct.unpack(
                        "<BBxxI", struct.pack("<q", group.data[2]))
                check_response(respcode, group.data)
                yield RemoteMemResponse(
                        version, opcode, xactid, group.timestamp)

def format_log(f):
    flits = parse_flits(f)
    groups = group_flits(flits)
    packets = parse_packets(groups)

    for pkt in packets:
        print(pkt)

def main():
    if len(sys.argv) < 2:
        format_log(sys.stdin)
    else:
        with open(sys.argv[1]) as f:
            format_log(f)

if __name__ == "__main__":
    main()
