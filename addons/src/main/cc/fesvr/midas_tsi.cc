#include "midas_tsi.h"

int midas_tsi_t::host_thread(void *arg)
{
  midas_tsi_t *tsi = static_cast<midas_tsi_t*>(arg);
  tsi->run();

  while (true)
    tsi->target->switch_to();

  return 0;
}

midas_tsi_t::midas_tsi_t(const std::vector<std::string>& args): midas_fesvr_t(args)
{
  target = midas_context_t::current();
  host.init(host_thread, this);
}

midas_tsi_t::~midas_tsi_t()
{
}

void midas_tsi_t::tick()
{
  host.switch_to();
}

void midas_tsi_t::wait()
{
  target->switch_to();
}

void midas_tsi_t::send_word(uint32_t word)
{
  out_data.push_back(word);
}

uint32_t midas_tsi_t::recv_word()
{
  uint32_t word = in_data.front();
  in_data.pop_front();
  return word;
}

bool midas_tsi_t::data_available()
{
  return !in_data.empty();
}

bool midas_tsi_t::recv_loadmem_req(fesvr_loadmem_t& loadmem) {
  if (loadmem_reqs.empty()) return false;
  auto r = loadmem_reqs.front();
  loadmem.addr = r.addr;
  loadmem.size = r.size;
  loadmem_reqs.pop_front();
  return true;
}

void midas_tsi_t::recv_loadmem_data(void* buf, size_t len) {
  std::copy(loadmem_data.begin(), loadmem_data.begin() + len, (char*)buf);
  loadmem_data.erase(loadmem_data.begin(), loadmem_data.begin() + len);
}
