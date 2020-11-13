// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0
/*
 * Copyright (C) 2017-2019 Regents of the University of California
 */

#include <linux/clk.h>
#include <linux/io.h>
#include <linux/cpu.h>
#include <linux/cpufreq.h>
#include <linux/module.h>
#include <linux/platform_device.h>

#define FREQ_NUM 2
#define CMUX_REG_ADDR 0x101000

static volatile __u8 __iomem * muxctrl;

struct cpufreq_frequency_table firechip_freq_table[FREQ_NUM+1] = {
	{ .frequency = 3200000,	},
	{ .frequency = 6400000,	},
	{ .frequency = CPUFREQ_TABLE_END, },
};

static int firechip_cpufreq_init(struct cpufreq_policy *policy) {
    muxctrl = ioremap((unsigned long)(CMUX_REG_ADDR), 1);
    if (!muxctrl) {
        pr_err("firechip-cpufreq:failed to remap control register addr\n");
        return -ENOMEM;
    }
    cpufreq_generic_init(policy, firechip_freq_table, 0);
    return 0;
}

static int firechip_cpufreq_target_index(struct cpufreq_policy *policy, unsigned int index) {
	printk(KERN_ALERT "Switching target frequency to %d kHz\n",
            firechip_freq_table[index].frequency);
    *muxctrl = index ^ 1 ;
    return 0;
}

static struct cpufreq_driver firechip_cpufreq_driver = {
	.name		= "fchip-freq",
	.verify		= cpufreq_generic_frequency_table_verify,
	.target_index = firechip_cpufreq_target_index,
	.init		= firechip_cpufreq_init,
	//.get		= cpufreq_generic_get,
	//.exit		= firechip-cpufreqfchip_freq_cpu_exit,
	//.attr		= cpufreq_generic_attr,
};


static int firechip_cpufreq_probe(struct platform_device *pdev)
{
	printk(KERN_ALERT "Loading firechip-cpufreq driver\n");
    return cpufreq_register_driver(&firechip_cpufreq_driver);
};

static int firechip_cpufreq_remove(struct platform_device *pdev)
{
	printk(KERN_ALERT "Unloading firechip-cpufreq driver\n");
	return 0;
}

static struct platform_driver firechip_cpufreq_pdriver = {
	.driver = {
		.name = "firechip-cpufreq",
	},
	.probe = firechip_cpufreq_probe,
	.remove = firechip_cpufreq_remove,
};
//module_platform_driver(firechip_cpufreq_pdriver);


static int firechip_cpufreq_init2(void)
{
	printk(KERN_ALERT "Loading firechip-cpufreq driver\n");
    return cpufreq_register_driver(&firechip_cpufreq_driver);
};

static void firechip_cpufreq_exit(void)
{
    iounmap(muxctrl);
	printk(KERN_ALERT "Unloading firechip-cpufreq driver\n");
}
module_init(firechip_cpufreq_init2)
module_exit(firechip_cpufreq_exit)

MODULE_LICENSE("GPL");
