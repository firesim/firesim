	.text
	.global _start
_start:
	/* end trigger */
	addi x0, x2, 0
	/* exit syscall */
	li a5, 0
	li a7, 94
	ecall
	/* trap */
	ebreak
