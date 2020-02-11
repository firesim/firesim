	.text
	.global _start
_start:
	/* start trigger */
	addi x0, x1, 0
	/* exit syscall */
	li a5, 0
	li a7, 94
	ecall
	/* trap */
	ebreak
