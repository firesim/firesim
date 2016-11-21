#ifdef VCS

extern "C" {
void tick(
  vc_handle reset,
  vc_handle fin,
  vc_handle err,

  vc_handle master_ar_valid,
  vc_handle master_ar_ready,
  vc_handle master_ar_bits_addr,
  vc_handle master_ar_bits_id,
  vc_handle master_ar_bits_size,
  vc_handle master_ar_bits_len,

  vc_handle master_aw_valid,
  vc_handle master_aw_ready,
  vc_handle master_aw_bits_addr,
  vc_handle master_aw_bits_id,
  vc_handle master_aw_bits_size,
  vc_handle master_aw_bits_len,

  vc_handle master_w_valid,
  vc_handle master_w_ready,
  vc_handle master_w_bits_strb,
  vc_handle master_w_bits_data,
  vc_handle master_w_bits_last,

  vc_handle master_r_valid,
  vc_handle master_r_ready,
  vc_handle master_r_bits_resp,
  vc_handle master_r_bits_id,
  vc_handle master_r_bits_data,
  vc_handle master_r_bits_last,

  vc_handle master_b_valid,
  vc_handle master_b_ready,
  vc_handle master_b_bits_resp,
  vc_handle master_b_bits_id,

  vc_handle slave_ar_valid,
  vc_handle slave_ar_ready,
  vc_handle slave_ar_bits_addr,
  vc_handle slave_ar_bits_id,
  vc_handle slave_ar_bits_size,
  vc_handle slave_ar_bits_len,

  vc_handle slave_aw_valid,
  vc_handle slave_aw_ready,
  vc_handle slave_aw_bits_addr,
  vc_handle slave_aw_bits_id,
  vc_handle slave_aw_bits_size,
  vc_handle slave_aw_bits_len,

  vc_handle slave_w_valid,
  vc_handle slave_w_ready,
  vc_handle slave_w_bits_strb,
  vc_handle slave_w_bits_data,
  vc_handle slave_w_bits_last,

  vc_handle slave_r_valid,
  vc_handle slave_r_ready,
  vc_handle slave_r_bits_resp,
  vc_handle slave_r_bits_id,
  vc_handle slave_r_bits_data,
  vc_handle slave_r_bits_last,

  vc_handle slave_b_valid,
  vc_handle slave_b_ready,
  vc_handle slave_b_bits_resp,
  vc_handle slave_b_bits_id
) {
  uint32_t master_r_data[MASTER_DATA_SIZE];
  for (size_t i = 0 ; i < MASTER_DATA_SIZE ; i++) {
    master_r_data[i] = vc_4stVectorRef(master_r_bits_data)[i].d;
  }
  uint32_t slave_w_data[SLAVE_DATA_SIZE];
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    slave_w_data[i] = vc_4stVectorRef(slave_w_bits_data)[i].d;
  }

  vc_putScalar(master_aw_valid, master->aw_valid());
  vc_putScalar(master_ar_valid, master->ar_valid());
  vc_putScalar(master_w_valid, master->w_valid());
  vc_putScalar(master_w_bits_last, master->w_last());
  vc_putScalar(master_r_ready, master->r_ready());
  vc_putScalar(master_b_ready, master->b_ready());

  vec32 md[MASTER_DATA_SIZE];
  md[0].c = 0;
  md[0].d = master->aw_id();
  vc_put4stVector(master_aw_bits_id, md);
  md[0].c = 0;
  md[0].d = master->aw_addr();
  vc_put4stVector(master_aw_bits_addr, md);
  md[0].c = 0;
  md[0].d = master->aw_size();
  vc_put4stVector(master_aw_bits_size, md);
  md[0].c = 0;
  md[0].d = master->aw_len();
  vc_put4stVector(master_aw_bits_len, md);
  md[0].c = 0;
  md[0].d = master->ar_id();
  vc_put4stVector(master_ar_bits_id, md);
  md[0].c = 0;
  md[0].d = master->ar_addr();
  vc_put4stVector(master_ar_bits_addr, md);
  md[0].c = 0;
  md[0].d = master->ar_size();
  vc_put4stVector(master_ar_bits_size, md);
  md[0].c = 0;
  md[0].d = master->ar_len();
  vc_put4stVector(master_ar_bits_len, md);
  md[0].c = 0;
  md[0].d = master->w_strb();
  vc_put4stVector(master_w_bits_strb, md);

  for (size_t i = 0 ; i < MASTER_DATA_SIZE ; i++) {
    md[i].c = 0;
    md[i].d = ((uint32_t*) master->w_data())[i];
  }
  vc_put4stVector(master_w_bits_data, md);

  try {
    master->tick(
      is_reset,
      vc_getScalar(master_ar_ready),
      vc_getScalar(master_aw_ready),
      vc_getScalar(master_w_ready),
      vc_4stVectorRef(master_r_bits_id)->d,
      master_r_data,
      vc_getScalar(master_r_bits_last),
      vc_getScalar(master_r_valid),
      vc_4stVectorRef(master_b_bits_id)->d,
      vc_getScalar(master_b_valid)
    );

    slave->tick(
      is_reset,
      vc_getScalar(slave_ar_valid),
      vc_4stVectorRef(slave_ar_bits_addr)->d,
      vc_4stVectorRef(slave_ar_bits_id)->d,
      vc_4stVectorRef(slave_ar_bits_size)->d,
      vc_4stVectorRef(slave_ar_bits_len)->d,

      vc_getScalar(slave_aw_valid),
      vc_4stVectorRef(slave_aw_bits_addr)->d,
      vc_4stVectorRef(slave_aw_bits_id)->d,
      vc_4stVectorRef(slave_aw_bits_size)->d,
      vc_4stVectorRef(slave_aw_bits_len)->d,

      vc_getScalar(slave_w_valid),
      vc_4stVectorRef(slave_w_bits_strb)->d,
      slave_w_data,
      vc_getScalar(slave_w_bits_last),

      vc_getScalar(slave_r_ready),
      vc_getScalar(slave_b_ready)
    );
  } catch(std::exception &e) {
    vcs_fin = true;
    vcs_err = true;
    fprintf(stderr, "Exception in tick(): %s\n", e.what());
  }

  vc_putScalar(slave_aw_ready, slave->aw_ready());
  vc_putScalar(slave_ar_ready, slave->ar_ready());
  vc_putScalar(slave_w_ready, slave->w_ready());
  vc_putScalar(slave_b_valid, slave->b_valid());
  vc_putScalar(slave_r_valid, slave->r_valid());
  vc_putScalar(slave_r_bits_last, slave->r_last());

  vec32 sd[SLAVE_DATA_SIZE];
  sd[0].c = 0;
  sd[0].d = slave->b_id();
  vc_put4stVector(slave_b_bits_id, sd);
  sd[0].c = 0;
  sd[0].d = slave->b_resp();
  vc_put4stVector(slave_b_bits_resp, sd);
  sd[0].c = 0;
  sd[0].d = slave->r_id();
  vc_put4stVector(slave_r_bits_id, sd);
  sd[0].c = 0;
  sd[0].d = slave->r_resp();
  vc_put4stVector(slave_r_bits_resp, sd);
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    sd[i].c = 0;
    sd[i].d = ((uint32_t*) slave->r_data())[i];
  }
  vc_put4stVector(slave_r_bits_data, sd);

  vc_putScalar(reset, is_reset);
  vc_putScalar(fin, vcs_fin);
  vc_putScalar(err, vcs_err);

  main_time++;

  if (!vcs_fin) host->switch_to();
  else vcs_fin = false;
}
}

#else

void tick() {
  top->clock = 1;
  top->eval();
#if VM_TRACE
  if (tfp) tfp->dump(main_time);
#endif // VM_TRACE
  main_time++;

  top->io_master_aw_valid = master->aw_valid();
  top->io_master_aw_bits_id = master->aw_id();
  top->io_master_aw_bits_addr = master->aw_addr();
  top->io_master_aw_bits_size = master->aw_size();
  top->io_master_aw_bits_len = master->aw_len();

  top->io_master_ar_valid = master->ar_valid();
  top->io_master_ar_bits_id = master->ar_id();
  top->io_master_ar_bits_addr = master->ar_addr();
  top->io_master_ar_bits_size = master->ar_size();
  top->io_master_ar_bits_len = master->ar_len();

  top->io_master_w_valid = master->w_valid();
  top->io_master_w_bits_strb = master->w_strb();
  top->io_master_w_bits_last = master->w_last();

  top->io_master_r_ready = master->r_ready();
  top->io_master_b_ready = master->b_ready();
#if CHANNEL_DATA_BITS > 64
  memcpy(top->io_master_w_bits_data, master->w_data(), MMIO_WIDTH);
#else
  memcpy(&top->io_master_w_bits_data, master->w_data(), MMIO_WIDTH);
#endif

  top->io_slave_aw_ready = slave->aw_ready();
  top->io_slave_ar_ready = slave->ar_ready();
  top->io_slave_w_ready = slave->w_ready();
  top->io_slave_b_valid = slave->b_valid();
  top->io_slave_b_bits_id = slave->b_id();
  top->io_slave_b_bits_resp = slave->b_resp();
  top->io_slave_r_valid = slave->r_valid();
  top->io_slave_r_bits_id = slave->r_id();
  top->io_slave_r_bits_resp = slave->r_resp();
  top->io_slave_r_bits_last = slave->r_last();
#if MEM_DATA_BITS > 64
  memcpy(top->io_slave_r_bits_data, slave->r_data(), MEM_WIDTH);
#else
  memcpy(&top->io_slave_r_bits_data, slave->r_data(), MEM_WIDTH);
#endif

  master->tick(
    top->reset,
    top->io_master_ar_ready,
    top->io_master_aw_ready,
    top->io_master_w_ready,
    top->io_master_r_bits_id,
#if CHANNEL_DATA_BITS > 64
    top->io_master_r_bits_data,
#else
    &top->io_master_r_bits_data,
#endif
    top->io_master_r_bits_last,
    top->io_master_r_valid,
    top->io_master_b_bits_id,
    top->io_master_b_valid
  );

  slave->tick(
    top->reset,
    top->io_slave_ar_valid,
    top->io_slave_ar_bits_addr,
    top->io_slave_ar_bits_id,
    top->io_slave_ar_bits_size,
    top->io_slave_ar_bits_len,

    top->io_slave_aw_valid,
    top->io_slave_aw_bits_addr,
    top->io_slave_aw_bits_id,
    top->io_slave_aw_bits_size,
    top->io_slave_aw_bits_len,

    top->io_slave_w_valid,
    top->io_slave_w_bits_strb,
#if MEM_DATA_BITS > 64
    top->io_slave_w_bits_data,
#else
    &top->io_slave_w_bits_data,
#endif
    top->io_slave_w_bits_last,
  
    top->io_slave_r_ready,
    top->io_slave_b_ready
  );

  top->clock = 0;
  top->eval();
#if VM_TRACE
  if (tfp) tfp->dump(main_time);
#endif // VM_TRACE
  main_time++;
}

#endif // VCS
