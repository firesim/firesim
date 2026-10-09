import os
import re
from os.path import join as pjoin

# File paths
user_data_path = pjoin(os.path.dirname(os.path.abspath(__file__)), "..", "deploy/vm-cloud-init-configs/user-data")
pubkey_path = pjoin(os.path.dirname(os.path.abspath(__file__)), "..", "deploy/vm-cloud-init-configs/firesim_vm_ed25519.pub")

# Load the new SSH public key
with open(pubkey_path, "r") as f:
    new_pubkey = f.read().strip()

# Load the YAML file as raw text
with open(user_data_path, "r") as f:
    content = f.read()

# Regex: match from 'ssh_authorized_keys:' to the line ending with 'ExternallyProvisionedWithVMIsolation'
pattern = re.compile(
    r'(ssh_authorized_keys:\n(?:\s+- .*\n)*?\s+- .*ExternallyProvisionedWithVMIsolation\s*\n)',
    re.MULTILINE
)

# Replacement block
replacement = f"ssh_authorized_keys:\n          - {new_pubkey}\n"

# Perform the substitution
new_content, count = pattern.subn(replacement, content, count=1)

if count == 0:
    raise RuntimeError("Did not find ssh_authorized_keys block to replace.")

# Write back the updated content
with open(user_data_path, "w") as f:
    f.write(new_content)
