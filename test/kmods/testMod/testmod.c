#include <linux/init.h>
#include <linux/module.h>
#include <linux/kernel.h>

MODULE_LICENSE("GPL");
MODULE_AUTHOR("Nathan Pemberton");

MODULE_DESCRIPTION("FireMarshal test of a novel kernel module (not overriding any existing module.");
MODULE_VERSION("1.00");

static int __init testmod_init(void)
{
 printk(KERN_INFO "testmod loaded \n");
 return 0;
}

static void __exit testmod_exit(void)
{
	return;
}

module_init(testmod_init);
module_exit(testmod_exit);
