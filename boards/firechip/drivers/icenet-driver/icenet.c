#include <linux/module.h>
#include <linux/errno.h>
#include <linux/types.h>
#include <linux/circ_buf.h>

#include <linux/interrupt.h>
#include <linux/irq.h>
#include <linux/irqdomain.h>

#include <linux/of_address.h>
#include <linux/of_pci.h>
#include <linux/of_platform.h>
#include <linux/of_irq.h>

#include <linux/netdevice.h>
#include <linux/etherdevice.h>

#define ICENET_NAME "icenet"
#define ICENET_SEND_REQ 0
#define ICENET_RECV_REQ 8
#define ICENET_SEND_COMP 16
#define ICENET_RECV_COMP 18
#define ICENET_COUNTS 20
#define ICENET_MACADDR 24
#define ICENET_INTMASK 32

#define ICENET_INTMASK_TX 1
#define ICENET_INTMASK_RX 2
#define ICENET_INTMASK_BOTH 3

#define ALIGN_BYTES 8
#define ALIGN_MASK 0x7
#define ALIGN_SHIFT 3
#define MAX_FRAME_SIZE (190 * ALIGN_BYTES)
#define DMA_PTR_ALIGN(p) ((typeof(p)) (__ALIGN_KERNEL((uintptr_t) (p), ALIGN_BYTES)))
#define DMA_LEN_ALIGN(n) (((((n) - 1) >> ALIGN_SHIFT) + 1) << ALIGN_SHIFT)
#define MACADDR_BYTES 6

#define ICENET_RING_SIZE 64
/* #define ICENET_FAKE_CHECKSUM */

struct sk_buff_cq_entry {
	struct sk_buff *skb;
};

struct sk_buff_cq {
	struct sk_buff_cq_entry entries[ICENET_RING_SIZE];
	int head;
	int tail;
};

#define SK_BUFF_CQ_COUNT(cq) CIRC_CNT(cq.head, cq.tail, ICENET_RING_SIZE)
#define SK_BUFF_CQ_SPACE(cq) CIRC_SPACE(cq.head, cq.tail, ICENET_RING_SIZE)

static inline void sk_buff_cq_init(struct sk_buff_cq *cq)
{
	cq->head = 0;
	cq->tail = 0;
}

static inline void sk_buff_cq_push(
		struct sk_buff_cq *cq, struct sk_buff *skb)
{
	cq->entries[cq->head].skb = skb;
	cq->head = (cq->head + 1) & (ICENET_RING_SIZE - 1);
}

static inline struct sk_buff *sk_buff_cq_pop(struct sk_buff_cq *cq)
{
	struct sk_buff *skb;

	skb = cq->entries[cq->tail].skb;
	cq->tail = (cq->tail + 1) & (ICENET_RING_SIZE - 1);

	return skb;
}

static inline int sk_buff_cq_tail_nsegments(struct sk_buff_cq *cq)
{
	struct sk_buff *skb;

	skb = cq->entries[cq->tail].skb;

	return skb_shinfo(skb)->nr_frags + 1;
}

struct icenet_device {
	struct device *dev;
	void __iomem *iomem;
	struct sk_buff_cq send_cq;
	struct sk_buff_cq recv_cq;
	spinlock_t tx_lock;
	spinlock_t rx_lock;
	int tx_irq;
	int rx_irq;
};

static inline int send_req_avail(struct icenet_device *nic)
{
	return ioread32(nic->iomem + ICENET_COUNTS) & 0xff;
}

static inline int recv_req_avail(struct icenet_device *nic)
{
	return (ioread32(nic->iomem + ICENET_COUNTS) >> 8) & 0xff;
}

static inline int send_comp_avail(struct icenet_device *nic)
{
	return (ioread32(nic->iomem + ICENET_COUNTS) >> 16) & 0xff;
}

static inline int recv_comp_avail(struct icenet_device *nic)
{
	return (ioread32(nic->iomem + ICENET_COUNTS) >> 24) & 0xff;
}

static inline void set_intmask(struct icenet_device *nic, uint32_t mask)
{
	atomic_t *mem = nic->iomem + ICENET_INTMASK;
	atomic_fetch_or(mask, mem);
}

static inline void clear_intmask(struct icenet_device *nic, uint32_t mask)
{
	atomic_t *mem = nic->iomem + ICENET_INTMASK;
	atomic_fetch_and(~mask, mem);
}

static inline void post_send_frag(
		struct icenet_device *nic, skb_frag_t *frag, int last)
{
	uintptr_t addr = page_to_phys(frag->page.p) + frag->page_offset;
	uint64_t len = frag->size, partial = !last, packet;

	packet = (partial << 63) | (len << 48) | (addr & 0xffffffffffffL);
	iowrite64(packet, nic->iomem + ICENET_SEND_REQ);
}

static inline void post_send(
		struct icenet_device *nic, struct sk_buff *skb)
{
	uintptr_t addr = virt_to_phys(skb->data);
	uint64_t len, partial, packet;
	struct skb_shared_info *shinfo = skb_shinfo(skb);
	int i;

	if (shinfo->nr_frags > 0) {
		len = skb_headlen(skb);
		partial = 1;
	} else {
		len = skb->len;
		partial = 0;
	}

	addr -= NET_IP_ALIGN;
	len += NET_IP_ALIGN;

	packet = (partial << 63) | (len << 48) | (addr & 0xffffffffffffL);
	iowrite64(packet, nic->iomem + ICENET_SEND_REQ);

	for (i = 0; i < shinfo->nr_frags; i++) {
		skb_frag_t *frag = &shinfo->frags[i];
		int last = i == (shinfo->nr_frags-1);
		post_send_frag(nic, frag, last);
	}

	sk_buff_cq_push(&nic->send_cq, skb);

//	printk(KERN_DEBUG "IceNet: tx addr=%lx len=%llu\n", addr, len);
}

static inline void post_recv(
		struct icenet_device *nic, struct sk_buff *skb)
{
	int align = DMA_PTR_ALIGN(skb->data) - skb->data;
	uintptr_t addr;

	skb_reserve(skb, align);
	addr = virt_to_phys(skb->data);

	iowrite64(addr, nic->iomem + ICENET_RECV_REQ);
	sk_buff_cq_push(&nic->recv_cq, skb);
}

static inline int send_space(struct icenet_device *nic)
{
	int avail = send_req_avail(nic);
	int space = SK_BUFF_CQ_SPACE(nic->send_cq);

	return (avail < space) ? avail : space;
}

static void complete_send(struct net_device *ndev)
{
	struct icenet_device *nic = netdev_priv(ndev);
	struct sk_buff *skb;
	int i, n, nsegs;

	n = send_comp_avail(nic);

	while (n > 0) {
		BUG_ON(SK_BUFF_CQ_COUNT(nic->send_cq) == 0);
		nsegs = sk_buff_cq_tail_nsegments(&nic->send_cq);

		if (nsegs > n)
			break;

		for (i = 0; i < nsegs; i++)
			ioread16(nic->iomem + ICENET_SEND_COMP);

		skb = sk_buff_cq_pop(&nic->send_cq);
		dev_consume_skb_irq(skb);
		n -= nsegs;
	}

	if (send_space(nic) > 0 && netif_queue_stopped(ndev))
		netif_wake_queue(ndev);
}

static void complete_recv(struct net_device *ndev)
{
	struct icenet_device *nic = netdev_priv(ndev);
	struct sk_buff *skb;
	int len;

	while (recv_comp_avail(nic) > 0) {
		len = ioread16(nic->iomem + ICENET_RECV_COMP);
		skb = sk_buff_cq_pop(&nic->recv_cq);
		skb_put(skb, len);
		skb_pull(skb, NET_IP_ALIGN);

#ifdef ICENET_FAKE_CHECKSUM
        skb->ip_summed = CHECKSUM_UNNECESSARY;
#endif
		skb->dev = ndev;
		skb->protocol = eth_type_trans(skb, ndev);
		ndev->stats.rx_packets++;
		ndev->stats.rx_bytes += len;
		netif_rx(skb);

//		printk(KERN_DEBUG "IceNet: rx addr=%p, len=%d\n",
//				skb->data, len);
	}
}

static void alloc_recv(struct net_device *ndev)
{
	struct icenet_device *nic = netdev_priv(ndev);
	int hw_recv_cnt = recv_req_avail(nic);
	int sw_recv_cnt = SK_BUFF_CQ_SPACE(nic->recv_cq);
	int recv_cnt = (hw_recv_cnt < sw_recv_cnt) ? hw_recv_cnt : sw_recv_cnt;

	for ( ; recv_cnt > 0; recv_cnt--) {
		struct sk_buff *skb;
		skb = netdev_alloc_skb(ndev, MAX_FRAME_SIZE);
		post_recv(nic, skb);
	}
}

static irqreturn_t icenet_tx_isr(int irq, void *data)
{
	struct net_device *ndev = data;
	struct icenet_device *nic = netdev_priv(ndev);

	if (irq != nic->tx_irq)
		return IRQ_NONE;

	spin_lock(&nic->tx_lock);

	complete_send(ndev);

	spin_unlock(&nic->tx_lock);

	return IRQ_HANDLED;
}

static irqreturn_t icenet_rx_isr(int irq, void *data)
{
	struct net_device *ndev = data;
	struct icenet_device *nic = netdev_priv(ndev);

	if (irq != nic->rx_irq)
		return IRQ_NONE;

	spin_lock(&nic->rx_lock);

	complete_recv(ndev);
	alloc_recv(ndev);

	spin_unlock(&nic->rx_lock);

	return IRQ_HANDLED;
}

static int icenet_parse_addr(struct net_device *ndev)
{
	struct icenet_device *nic = netdev_priv(ndev);
	struct device *dev = nic->dev;
	struct device_node *node = dev->of_node;
	struct resource regs;
	int err;

	err = of_address_to_resource(node, 0, &regs);
	if (err) {
		dev_err(dev, "missing \"reg\" property\n");
		return err;
	}

	nic->iomem = devm_ioremap_resource(dev, &regs);
	if (IS_ERR(nic->iomem)) {
		dev_err(dev, "could not remap io address %llx", regs.start);
		return PTR_ERR(nic->iomem);
	}

	return 0;
}

static int icenet_parse_irq(struct net_device *ndev)
{
	struct icenet_device *nic = netdev_priv(ndev);
	struct device *dev = nic->dev;
	struct device_node *node = dev->of_node;
	int err;

	nic->tx_irq = irq_of_parse_and_map(node, 0);
	err = devm_request_irq(dev, nic->tx_irq, icenet_tx_isr,
			IRQF_SHARED | IRQF_NO_THREAD,
			ICENET_NAME, ndev);
	if (err) {
		dev_err(dev, "could not obtain TX irq %d\n", nic->tx_irq);
		return err;
	}

	nic->rx_irq = irq_of_parse_and_map(node, 1);
	err = devm_request_irq(dev, nic->rx_irq, icenet_rx_isr,
			IRQF_SHARED | IRQF_NO_THREAD,
			ICENET_NAME, ndev);
	if (err) {
		dev_err(dev, "could not obtain RX irq %d\n", nic->rx_irq);
		return err;
	}

	return 0;
}

static int icenet_open(struct net_device *ndev)
{
	struct icenet_device *nic = netdev_priv(ndev);
	unsigned long flags;

	spin_lock_irqsave(&nic->rx_lock, flags);
	alloc_recv(ndev);
	spin_unlock_irqrestore(&nic->rx_lock, flags);

	netif_start_queue(ndev);
	set_intmask(nic, ICENET_INTMASK_BOTH);

	printk(KERN_DEBUG "IceNet: opened device\n");

	return 0;
}

static int icenet_stop(struct net_device *ndev)
{
	struct icenet_device *nic = netdev_priv(ndev);

	clear_intmask(nic, ICENET_INTMASK_BOTH);
	netif_stop_queue(ndev);

	printk(KERN_DEBUG "IceNet: stopped device\n");
	return 0;
}

static int icenet_start_xmit(struct sk_buff *skb, struct net_device *ndev)
{
	struct icenet_device *nic = netdev_priv(ndev);
	unsigned long flags;
	int space;

	spin_lock_irqsave(&nic->tx_lock, flags);

	space = send_space(nic);

	if (unlikely(space < skb_shinfo(skb)->nr_frags + 1)) {
		printk(KERN_WARNING "Not enough space in TX ring\n");
		netif_stop_queue(ndev);
		dev_kfree_skb_any(skb);
		ndev->stats.tx_dropped++;
		spin_unlock_irqrestore(&nic->tx_lock, flags);
		return NETDEV_TX_BUSY;
	}

	skb_tx_timestamp(skb);
	post_send(nic, skb);
	ndev->stats.tx_packets++;
	ndev->stats.tx_bytes += skb->len;

	spin_unlock_irqrestore(&nic->tx_lock, flags);

	return NETDEV_TX_OK;
}

static void icenet_init_mac_address(struct net_device *ndev)
{
	struct icenet_device *nic = netdev_priv(ndev);
	uint64_t macaddr = ioread64(nic->iomem + ICENET_MACADDR);

	ndev->addr_assign_type = NET_ADDR_PERM;
	memcpy(ndev->dev_addr, &macaddr, MACADDR_BYTES);

	if (!is_valid_ether_addr(ndev->dev_addr))
		printk(KERN_WARNING "Invalid MAC address\n");
}

static const struct net_device_ops icenet_ops = {
	.ndo_open = icenet_open,
	.ndo_stop = icenet_stop,
	.ndo_start_xmit = icenet_start_xmit
};

static int icenet_probe(struct platform_device *pdev)
{
	struct device *dev = &pdev->dev;
	struct net_device *ndev;
	struct icenet_device *nic;
	int ret;

	if (!dev->of_node)
		return -ENODEV;

	ndev = devm_alloc_etherdev(dev, sizeof(struct icenet_device));
	if (!ndev)
		return -ENOMEM;

	dev_set_drvdata(dev, ndev);
	nic = netdev_priv(ndev);
	nic->dev = dev;


	ether_setup(ndev);
	ndev->flags &= ~IFF_MULTICAST;
	ndev->netdev_ops = &icenet_ops;
#ifdef CONFIG_ICENET_FAKE_CHECKSUM
	ndev->hw_features = NETIF_F_SG | NETIF_F_IP_CSUM;
#else
	ndev->hw_features = NETIF_F_SG;
#endif

	ndev->features = ndev->hw_features;
	ndev->vlan_features = ndev->hw_features;

	spin_lock_init(&nic->tx_lock);
	spin_lock_init(&nic->rx_lock);
	sk_buff_cq_init(&nic->send_cq);
	sk_buff_cq_init(&nic->recv_cq);
	if ((ret = icenet_parse_addr(ndev)) < 0)
		return ret;

	icenet_init_mac_address(ndev);
	if ((ret = register_netdev(ndev)) < 0) {
		dev_err(dev, "Failed to register netdev\n");
		return ret;
	}

	if ((ret = icenet_parse_irq(ndev)) < 0)
		return ret;

	printk(KERN_INFO "Registered IceNet NIC %02x:%02x:%02x:%02x:%02x:%02x\n",
			ndev->dev_addr[0],
			ndev->dev_addr[1],
			ndev->dev_addr[2],
			ndev->dev_addr[3],
			ndev->dev_addr[4],
			ndev->dev_addr[5]);

	return 0;
}

static int icenet_remove(struct platform_device *pdev)
{
	struct net_device *ndev;
	ndev = platform_get_drvdata(pdev);
	unregister_netdev(ndev);
	return 0;
}

static struct of_device_id icenet_of_match[] = {
	{ .compatible = "ucbbar,ice-nic" },
	{}
};

static struct platform_driver icenet_driver = {
	.driver = {
		.name = ICENET_NAME,
		.of_match_table = icenet_of_match,
		.suppress_bind_attrs = true
	},
	.probe = icenet_probe,
	.remove = icenet_remove
};

module_platform_driver(icenet_driver);
MODULE_DESCRIPTION("Drives the FireChip IceNIC ethernet device (used in firesim)");
MODULE_LICENSE("GPL");
