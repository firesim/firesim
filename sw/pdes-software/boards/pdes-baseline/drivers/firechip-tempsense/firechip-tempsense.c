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
#include <linux/thermal.h>

#include <linux/of_platform.h>

#define TEMP_ADDR 0x102000
//#define PASSIVE_DELAY 1000 /* ms */
#define PASSIVE_DELAY 100 /* ms */
//#define POLLING_DELAY 1000 /* ms */
#define POLLING_DELAY 100 /* ms */

static volatile __u8 __iomem * tempreg;

struct thermal_zone_device *thermal = NULL;
struct cpufreq_policy * policy;
static bool use_emul_temp = false;
static int dummy_temp = 32;

enum firechip_thermal_trip {
    FIRECHIP_TRIP_PASSIVE,
    FIRECHIP_TRIP_NUM,
};

#define TRIP_TEMP 40

static int firechip_get_temp(struct thermal_zone_device *thermal, int *temp) {
    unsigned long reg;
    *temp = (*tempreg) / 2;
    pr_alert("Current temperature: %d C\n", *temp);
    return 0;
}

static int firechip_set_emul_temp(struct thermal_zone_device *thermal, int temp) {
    unsigned long reg;
    pr_alert("Forcing temperature to %d C\n", temp);
    dummy_temp = temp;
    return 0;
}

static int firechip_bind_thermal(struct thermal_zone_device *tz, struct thermal_cooling_device *cdev) {
    int ret;
    pr_alert("Binding thermal zone device and cooling device\n");
	ret = thermal_zone_bind_cooling_device(tz, 0, cdev,
					       THERMAL_NO_LIMIT,
					       THERMAL_NO_LIMIT,
					       THERMAL_WEIGHT_DEFAULT);
    return 0;
}

static int firechip_get_trip_type(struct thermal_zone_device *tz, int trip, enum thermal_trip_type *type)
{
	*type = FIRECHIP_TRIP_PASSIVE;
	return 0;
}

static int firechip_get_trip_temp(struct thermal_zone_device *tz, int trip, int *temp)
{
	*temp = TRIP_TEMP;
	return 0;
}

static struct thermal_zone_device_ops ops = {
	.get_temp = firechip_get_temp,
    .bind = firechip_bind_thermal,
    .get_trip_type = firechip_get_trip_type,
    .get_trip_temp = firechip_get_trip_temp,
    .set_emul_temp = firechip_set_emul_temp,

};


//static struct of_device_id tempsense_of_match[] = {
//	{ .compatible = "ucbbar,tempsense0" },
//	{}
//};

//static int firechip_tempsense_probe(struct platform_device *pdev)
//{
//	printk(KERN_ALERT "Loading firechip-tempsense driver\n");
//    return 0;
//};
//
//static int firechip_tempsense_remove(struct platform_device *pdev)
//{
//	printk(KERN_ALERT "Unloading firechip-tempsense driver\n");
//    iounmap(temp);
//	return 0;
//}
//
//static struct platform_driver firechip_tempsense_pdriver = {
//	.driver = {
//        .compatible = "
//		.name = "firechip-tempsense",
//	},
//	.probe = firechip_tempsense_probe,
//	.remove = firechip_tempsense_remove,
//};
//module_platform_driver(firechip_tempsense_pdriver);


static int firechip_tempsense_init2(void)
{
	pr_alert("Loading firechip-tempsense driver\n");

    tempreg = ioremap((unsigned long)(TEMP_ADDR), 1);
    if (!tempreg) {
        pr_err("firechip-cpufreq:failed to remap control register addr\n");
        return -ENOMEM;
    }

	thermal = thermal_zone_device_register("firechip_thermal", FIRECHIP_TRIP_NUM, FIRECHIP_TRIP_PASSIVE,
					       NULL, &ops, NULL, PASSIVE_DELAY, POLLING_DELAY);
	if (IS_ERR(thermal)) {
		pr_err("Failed to register thermal zone device\n");
		return PTR_ERR(thermal);
	}

	policy = cpufreq_cpu_get(0);
	if (!policy) {
		pr_err("%s: CPUFreq policy not found\n", __func__);
		return -EPROBE_DEFER;
	}
    cpufreq_cooling_register(policy);

    return 0;
};

static void firechip_tempsense_exit(void)
{
	printk(KERN_ALERT "Unloading firechip-tempsense driver\n");
    iounmap(tempreg);
	cpufreq_cpu_put(policy);
}

module_init(firechip_tempsense_init2)
module_exit(firechip_tempsense_exit)

MODULE_LICENSE("GPL");
