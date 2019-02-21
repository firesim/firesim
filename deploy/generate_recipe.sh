#!/bin/bash

#Defaults covered by the base configs
#L1I16K4W_L1D16K4W
#L1TLB32W_L2TLB64

target_configs=(
L1I32K8W_L1D32K8W
L1I16K8W_L1D16K8W
L1I8K4W_L1D8K4W
L1I8K2W_L1D8K2W
L1I4K8W_L1D4K8W
L1I4K4W_L1D4K4W
L1I4K2W_L1D4K2W
L1I4K1W_L1D4K1W
L1TLB8W_L2TLB0
L1TLB16W_L2TLB0
L1TLB32W_L2TLB0
L1TLB32W_L2TLB256
L1TLB32W_L2TLB1024
)

platform_configs=(
LBPBaseConfig
DRAMBaseConfig
LLCDRAMBaseConfig
)

for tconfig in "${target_configs[@]}"; do
    for platform_config in "${platform_configs[@]}"; do
        template="""[${tconfig}-${platform_config}]
DESIGN=FireSimNoNIC
TARGET_CONFIG=${tconfig}_CS152BaseTConfig
PLATFORM_CONFIG=${platform_config}
instancetype=c4.4xlarge
deploytriplet=None
"""
        echo "$template"
    done
done

for tconfig in "${target_configs[@]}"; do
    for platform_config in "${platform_configs[@]}"; do
        template="""${tconfig}-${platform_config}"""
        echo "$template"
    done
done
