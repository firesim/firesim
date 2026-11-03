    .section .gpu, "a"
    .globl gpu_kernel_start
gpu_kernel_start:
    .incbin "gpu_kernel.bin"
    .globl gpu_kernel_end
gpu_kernel_end:
