#include "serial.h"

serial_t::serial_t(simif_t* sim, fesvr_proxy_t* fesvr):
    endpoint_t(sim), fesvr(fesvr)
{
}

void serial_t::send() {
    if (data.in.fire()) {
        write(SERIALWIDGET_0(in_bits), data.in.bits);
        write(SERIALWIDGET_0(in_valid), data.in.valid);
    }
    if (data.out.fire()) {
        write(SERIALWIDGET_0(out_ready), data.out.ready);
    }
}

void serial_t::recv() {
    data.in.ready = read(SERIALWIDGET_0(in_ready));
    data.out.valid = read(SERIALWIDGET_0(out_valid));
    if (data.out.valid) {
        data.out.bits = read(SERIALWIDGET_0(out_bits));
    }
}

void serial_t::work() {
    data.out.ready = true;
    do {
        this->recv();

        data.in.valid = fesvr->data_available();
        if (data.in.fire()) {
            data.in.bits = fesvr->recv_word();
        }
        if (data.out.fire()) {
            fesvr->send_word(data.out.bits);
        }

        this->send();

        fesvr->tick();
    } while (data.in.fire() || data.out.fire());
}
