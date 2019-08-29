#include <linux/module.h>
#include <linux/errno.h>
#include <linux/types.h>

#include <linux/genhd.h>
#include <linux/blkdev.h>
#include <linux/bio.h>
#include <linux/fs.h>
#include <linux/hdreg.h>
#include <linux/blk-mq.h>

#include <linux/interrupt.h>
#include <linux/irq.h>
#include <linux/irqdomain.h>

#include <linux/of_address.h>
#include <linux/of_pci.h>
#include <linux/of_platform.h>
#include <linux/of_irq.h>

#include <asm/io.h>
#include <asm/page.h>

#define ICEBLK_NAME "iceblk"
#define ICEBLK_MINORS 16
#define ICEBLK_SECTOR_SIZE 512

#define ICEBLK_ADDR 0
#define ICEBLK_OFFSET 8
#define ICEBLK_LEN 12
#define ICEBLK_WRITE 16
#define ICEBLK_REQUEST 17
#define ICEBLK_NREQUEST 18
#define ICEBLK_COMPLETE 19
#define ICEBLK_NCOMPLETE 20
#define ICEBLK_NSECTORS 24
#define ICEBLK_MAX_REQUEST_LENGTH 28

struct iceblk_port {
	struct device *dev;
	void __iomem *iomem;
	struct gendisk *gd;
	struct request_queue *queue;
	struct list_head *reqbuf;
	struct blk_mq_tag_set tag_set;
	spinlock_t lock;
	int major;
	int irq;
	int qrunning;
};

struct iceblk_request {
	struct request *req;
	struct list_head list;
};

static struct block_device_operations iceblk_fops = {
	.owner = THIS_MODULE
};

static void iceblk_process_completions(struct iceblk_port *port)
{
	uint32_t ncomplete, tag;
	struct iceblk_request *breq;
	int i;

	ncomplete = ioread8(port->iomem + ICEBLK_NCOMPLETE);

	for (i = 0; i < ncomplete; i++) {
		tag = ioread8(port->iomem + ICEBLK_COMPLETE);
		BUG_ON(list_empty(&port->reqbuf[tag]));
		breq = list_entry(port->reqbuf[tag].next,
				struct iceblk_request, list);
		mb();
		blk_mq_complete_request(breq->req);
		list_del(&breq->list);
		kfree(breq);
	}

	if (ncomplete > 0 && !port->qrunning) {
		port->qrunning = 1;
		blk_mq_start_stopped_hw_queues(port->queue, true);
	}
}

static irqreturn_t iceblk_isr(int irq, void *data)
{
	struct iceblk_port *port = data;

	if (irq != port->irq)
		return IRQ_NONE;

	spin_lock(&port->lock);
	iceblk_process_completions(port);
	spin_unlock(&port->lock);

	return IRQ_HANDLED;
}

static struct iceblk_port *iceblk_req_port(struct request *req)
{
	return (struct iceblk_port *) req->rq_disk->private_data;
}

static void iceblk_queue_request(struct request *req, int write)
{
	struct iceblk_port *port = iceblk_req_port(req);
	uint64_t addr = page_to_phys(bio_page(req->bio)) + bio_offset(req->bio);
	uint32_t offset = blk_rq_pos(req);
	uint32_t len = blk_rq_sectors(req);
	uint32_t tag;
	struct iceblk_request *breq;

	iowrite64(addr,   port->iomem + ICEBLK_ADDR);
	iowrite32(offset, port->iomem + ICEBLK_OFFSET);
	iowrite32(len,    port->iomem + ICEBLK_LEN);
	iowrite8 (write,  port->iomem + ICEBLK_WRITE);
	mb();

	tag = ioread8(port->iomem + ICEBLK_REQUEST);
	breq = kmalloc(sizeof(struct iceblk_request), GFP_ATOMIC);
	breq->req = req;
	list_add_tail(&breq->list, &port->reqbuf[tag]);

	//printk(KERN_DEBUG
	//	"iceblk: %s [addr %llx] [off %x] [len %d] [tag: %d]\n",
	//	(write) ? "w" : "r", addr, offset, len, tag);
}

static blk_status_t iceblk_rq_handler(struct blk_mq_hw_ctx *hctx,
					const struct blk_mq_queue_data *bd)
{
	struct request *req = bd->rq;
	struct iceblk_port *port = iceblk_req_port(req);
	blk_status_t err = BLK_STS_OK;
	unsigned long flags;

	blk_mq_start_request(req);
	spin_lock_irqsave(&port->lock, flags);

	if (ioread8(port->iomem + ICEBLK_NREQUEST) == 0) {
		port->qrunning = 0;
		blk_mq_stop_hw_queue(hctx);
		err = BLK_STS_DEV_RESOURCE;
		goto out;
	}

	switch (req_op(req)) {
	case REQ_OP_DISCARD:
	case REQ_OP_FLUSH:
		break;
	case REQ_OP_READ:
		iceblk_queue_request(req, 0);
		break;
	case REQ_OP_WRITE:
		iceblk_queue_request(req, 1);
		break;
	default:
		dev_err(port->dev, "unhandleable iceblk request\n");
		err = BLK_STS_NOTSUPP;
	}

out:
	spin_unlock_irqrestore(&port->lock, flags);
	return err;
}

static void iceblk_complete_rq(struct request *req)
{
	blk_mq_end_request(req, BLK_STS_OK);
}

static int iceblk_parse_dt(struct iceblk_port *port)
{
	struct device *dev = port->dev;
	struct device_node *node = dev->of_node;
	struct resource regs;
	int err;

	err = of_address_to_resource(node, 0, &regs);
	if (err) {
		dev_err(dev, "missing \"reg\" property\n");
		return err;
	}

	port->iomem = devm_ioremap_resource(dev, &regs);
	if (IS_ERR(port->iomem)) {
		dev_err(dev, "could not remap io address %llx", regs.start);
		return PTR_ERR(port->iomem);
	}

	port->irq = irq_of_parse_and_map(node, 0);
	err = devm_request_irq(dev, port->irq, iceblk_isr,
			IRQF_SHARED | IRQF_NO_THREAD,
			ICEBLK_NAME, port);
	if (err) {
		dev_err(dev, "unable to request irq %d\n", port->irq);
		return err;
	}

	return 0;
}

static const struct blk_mq_ops iceblk_mq_ops = {
	.queue_rq = iceblk_rq_handler,
	.complete = iceblk_complete_rq,
};

static int iceblk_setup(struct iceblk_port *port)
{
	uint32_t nsectors = ioread32(port->iomem + ICEBLK_NSECTORS);
	struct device *dev = port->dev;
	uint32_t i, ntags, max_req_len;

	if (nsectors == 0) {
		dev_err(dev, "No disk attached.\n");
		return -ENODEV;
	}

	port->major = register_blkdev(0, ICEBLK_NAME);
	if (port->major < 0) {
		dev_err(dev, "Could not register iceblk\n");
		return port->major;
	}

	ntags = ioread8(port->iomem + ICEBLK_NREQUEST);
	max_req_len = ioread32(port->iomem + ICEBLK_MAX_REQUEST_LENGTH);
	port->qrunning = 1;
	port->reqbuf = devm_kzalloc(
			port->dev, ntags * sizeof(struct list_head), GFP_KERNEL);
	for (i = 0; i < ntags; i++)
		INIT_LIST_HEAD(&port->reqbuf[i]);

	spin_lock_init(&port->lock);
	port->queue = blk_mq_init_sq_queue(&port->tag_set,
		&iceblk_mq_ops, 2, BLK_MQ_F_SHOULD_MERGE);
	if (IS_ERR(port->queue)) {
		dev_err(dev, "Could not initialize blk_queue\n");
		goto exit_queue;
	}
	blk_queue_logical_block_size(port->queue, ICEBLK_SECTOR_SIZE);
	blk_queue_max_segments(port->queue, 1);
	blk_queue_max_hw_sectors(port->queue, max_req_len);

	port->gd = alloc_disk(ICEBLK_MINORS);
	if (!port->gd)
		goto exit_gendisk;
	port->gd->major = port->major;
	port->gd->first_minor = 0;
	port->gd->fops = &iceblk_fops;
	port->gd->queue = port->queue;
	port->gd->private_data = port;
	snprintf(port->gd->disk_name, 32, ICEBLK_NAME);
	set_capacity(port->gd, nsectors);
	add_disk(port->gd);

	printk(KERN_INFO "disk [%s] of loaded; "
			"%u sectors, %u tags, %u max request length\n",
			port->gd->disk_name, nsectors, ntags, max_req_len);

	return 0;

exit_gendisk:
	blk_cleanup_queue(port->queue);
	blk_mq_free_tag_set(&port->tag_set);
exit_queue:
	return -ENOMEM;
}

static int iceblk_probe(struct platform_device *pdev)
{
	struct device *dev = &pdev->dev;
	struct iceblk_port *port;
	int err;

	if (!dev->of_node)
		return -ENODEV;

	port = devm_kzalloc(dev, sizeof(*port), GFP_KERNEL);
	port->dev = dev;
	dev_set_drvdata(dev, port);

	err = iceblk_parse_dt(port);
	if (err) {
		dev_err(dev, "Parsing DeviceTree failed\n");
		return err;
	}

	err = iceblk_setup(port);
	if (err) {
		dev_err(dev, "Device setup failed\n");
		return err;
	}

	return 0;
}

static int iceblk_teardown(struct iceblk_port *port)
{
	del_gendisk(port->gd);
	put_disk(port->gd);
	blk_cleanup_queue(port->queue);
	unregister_blkdev(port->major, ICEBLK_NAME);
	return 0;
}

static int iceblk_remove(struct platform_device *pdev)
{
	struct device *dev = &pdev->dev;
	struct iceblk_port *port = dev_get_drvdata(dev);
	return iceblk_teardown(port);
}

static struct of_device_id iceblk_of_match[] = {
	{ .compatible = "ucbbar,blkdev" },
	{}
};

static struct platform_driver iceblk_driver = {
	.driver = {
		.name = ICEBLK_NAME,
		.of_match_table = iceblk_of_match,
		.suppress_bind_attrs = true,
	},
	.probe = iceblk_probe,
	.remove = iceblk_remove,
};
module_platform_driver(iceblk_driver);
MODULE_DESCRIPTION("Drives the generic FireChip block device (iceblk).");
MODULE_LICENSE("Dual BSD/GPL");
