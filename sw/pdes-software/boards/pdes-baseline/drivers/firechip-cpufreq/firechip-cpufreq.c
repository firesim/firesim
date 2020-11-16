// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0
/*
 * Copyright (C) 2017-2019 Regents of the University of California
 */

#include <linux/clk.h>
#include <linux/io.h>
#include <linux/cpu.h>
#include <linux/cpufreq.h>
#include <linux/cpu_cooling.h>
#include <linux/module.h>
#include <linux/platform_device.h>
#include <linux/pm_opp.h>
#include <linux/cpumask.h>

#define FREQ_NUM 2
#define CMUX_REG_ADDR 0x101000
#define FAST_FREQ 640000000L

static volatile __u8 __iomem * muxctrl;
static struct device *cpu_dev;

static struct cpufreq_frequency_table *firechip_freq_table;

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
    int ret;
	printk(KERN_ALERT "Loading firechip-cpufreq driver\n");
    // Hack: Just inject hardcoded OPPs
    cpu_dev = get_cpu_device(0);
    if (!cpu_dev) {
        printk(KERN_WARNING "%s: unable to get the CPU device\n", __func__);
        return -EINVAL;
    }
    ret = dev_pm_opp_add(cpu_dev, FAST_FREQ, 150000);
    if (ret) {
        dev_err(cpu_dev, "Unable to register OPPs\n");
        return ret;
    }

    ret = dev_pm_opp_add(cpu_dev, FAST_FREQ / 2, 150000);
    if (ret) {
        dev_err(cpu_dev, "Unable to register OPPs\n");
        return ret;
    }

    ret = dev_pm_opp_init_cpufreq_table(cpu_dev, &firechip_freq_table);
    if (ret) {
        dev_err(cpu_dev, "failed to init cpufreq table: %d\n", ret);
        return ret;
    }

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
