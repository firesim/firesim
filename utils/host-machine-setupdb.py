# we will create a list of BDFs mapped to FPGAs for firesim vm at default_fpga_vm_db
# this should only be run once on the host machine by the system administrator
import json
from pathlib import Path
import subprocess


defaultPath = Path("/opt/firesim-vm-host-db.json") # this won't be exposed to the user for now
# map fpga bdfs to empty schemas
# schema:
#     [
#         "fpga_bdf": {
#             busy: bool, # whether the FPGA is being used by a vm or not
#             vm_name: str, # name of the VM that is using the FPGA
#             vm_ip: str, # IP address of the VM that is using the FPGA
#             in_use_by: str, # name of the job/user that is using the FPGA
#         }
#     ]
# get all fpga bdfs
collect = subprocess.run("lspci | grep -i xilinx", capture_output=True, text=True, shell=True).stdout

bdfs = [
    {"busno": "0x" + i[:2], "devno": "0x" + i[3:5], "capno": "0x" + i[6:7]}
    for i in collect.splitlines()
    if len(i.strip()) >= 0
]
fpga_db = {}
for bdf in bdfs:
    busno = bdf["busno"]
    devno = bdf["devno"]
    capno = bdf["capno"]
    fpga_bdf = f"0000:{busno[2:]}:{devno[2:]}:{capno[2:]}"
    fpga_db[fpga_bdf] = {
        "busy": False,
        "vm_name": "",
        "vm_ip": "",
        "in_use_by": "",
    }
# write to file
with defaultPath.open("w") as f:
    json.dump(fpga_db, f, indent=4)