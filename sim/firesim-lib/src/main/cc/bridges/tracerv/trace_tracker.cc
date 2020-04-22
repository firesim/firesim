#include "trace_tracker.h"

//#define TRACETRACKER_LOG_PC_REGION
#include <regex>

std::string modpath_to_name(std::string &modpath)
{
    size_t name_start = modpath.rfind('/') + 1;
    size_t name_end = modpath.rfind('.');
    auto modname = modpath.substr(name_start, name_end - name_start);
    size_t dash_pos = 0;

    while ((dash_pos = modname.find('-', dash_pos)) != std::string::npos) {
        modname[dash_pos] = '_';
    }

    return modname;
}

TraceTracker::TraceTracker(
        std::string binary_with_dwarf, FILE * tracefile)
{
    this->bin_dump = new ObjdumpedBinary(binary_with_dwarf);
    this->tracefile = tracefile;
}

TraceTracker::TraceTracker(
        std::string binary_with_dwarf, FILE * tracefile,
        std::map<std::string, uint64_t> &modbasemap,
        std::vector<std::string> &modules)
{
    this->bin_dump = new ObjdumpedBinary(binary_with_dwarf);
    this->tracefile = tracefile;

    for (auto modpath : modules) {
        ObjdumpedBinary *dump = new ObjdumpedBinary(modpath);
        auto modname = modpath_to_name(modpath);
        auto modbase = modbasemap[modname];
        dump->relocate(modbase);
        mod_dumps.push_back(dump);
    }
}

void TraceTracker::addInstruction(uint64_t inst_addr, uint64_t cycle)
{
    Instr * this_instr = this->bin_dump->getInstrFromAddr(inst_addr);

    if (!this_instr) {
        for (auto mod : mod_dumps) {
            this_instr = mod->getInstrFromAddr(inst_addr);
            if (this_instr)
                break;
        }
    }

#ifdef TRACETRACKER_LOG_PC_REGION
    if (!this_instr) {
        fprintf(this->tracefile, "addr:%" PRIx64 ", fn:%s\n", inst_addr, "USERSPACE");
    } else {
        fprintf(this->tracefile, "addr:%" PRIx64 ", fn:%s\n", inst_addr, this_instr->function_name.c_str());
    }
    return;
#endif

    if (!this_instr) {
        if ((label_stack.size() == 1) && (std::string("USERSPACE_ALL").compare(label_stack[label_stack.size()-1]->label) == 0)) {
            LabelMeta * last_label = label_stack[label_stack.size()-1];
            last_label->end_cycle = cycle;
        } else {
            while (label_stack.size() > 0) {
                LabelMeta * pop_label = label_stack[label_stack.size()-1];
                label_stack.pop_back();
                pop_label->post_print(this->tracefile);
                delete pop_label;
                if (label_stack.size() > 0) {
                    LabelMeta * last_label = label_stack[label_stack.size()-1];
                    last_label->end_cycle = cycle;
                }
            }
            LabelMeta * new_label = new LabelMeta();
            new_label->label = std::string("USERSPACE_ALL");
            new_label->start_cycle = cycle;
            new_label->end_cycle = cycle;
            new_label->indent = label_stack.size() + 1;
            new_label->asm_sequence = false;
            label_stack.push_back(new_label);
            new_label->pre_print(this->tracefile);
        }
    } else {
        std::string label = this_instr->function_name;

        if ((label_stack.size() > 0) && (std::string("USERSPACE_ALL").compare(label_stack[label_stack.size()-1]->label) == 0)) {
            LabelMeta * pop_label = label_stack[label_stack.size()-1];
            label_stack.pop_back();
            pop_label->post_print(this->tracefile);
            delete pop_label;
        }

        if ((label_stack.size() > 0) && (label.compare(label_stack[label_stack.size()-1]->label) == 0)) {
            LabelMeta * last_label = label_stack[label_stack.size()-1];
            last_label->end_cycle = cycle;
        } else {
            if ((label_stack.size() > 0) and
                this_instr->in_asm_sequence and
                label_stack[label_stack.size()-1]->asm_sequence) {

                LabelMeta * pop_label = label_stack[label_stack.size()-1];
                label_stack.pop_back();
                pop_label->post_print(this->tracefile);
                delete pop_label;

                LabelMeta * new_label = new LabelMeta();
                new_label->label = label;
                new_label->start_cycle = cycle;
                new_label->end_cycle = cycle;
                new_label->indent = label_stack.size() + 1;
                new_label->asm_sequence = this_instr->in_asm_sequence;
                label_stack.push_back(new_label);
                new_label->pre_print(this->tracefile);
            } else if ((label_stack.size() > 0) and
                    (this_instr->is_callsite or !(this_instr->is_fn_entry))) {
                uint64_t unwind_start_level = (uint64_t)(-1);
                while ((label_stack.size() > 0) and
                        (label_stack[label_stack.size()-1]->label.compare(label) != 0)) {
                    LabelMeta * pop_label = label_stack[label_stack.size()-1];
                    label_stack.pop_back();
                    pop_label->post_print(this->tracefile);
                    if (unwind_start_level == (uint64_t)(-1)) {
                        unwind_start_level = pop_label->indent;
                    }
                    delete pop_label;
                    if (label_stack.size() > 0) {
                        LabelMeta * last_label = label_stack[label_stack.size()-1];
                        last_label->end_cycle = cycle;
                    }
                }
                if (label_stack.size() == 0) {
                    fprintf(this->tracefile, "WARN: STACK ZEROED WHEN WE WERE LOOKING FOR LABEL: %s, iaddr 0x%" PRIx64 "\n", label.c_str(), inst_addr);
                    fprintf(this->tracefile, "WARN: is_callsite was: %d, is_fn_entry was: %d\n", this_instr->is_callsite, this_instr->is_fn_entry);
                    fprintf(this->tracefile, "WARN: Unwind started at level: dec %" PRIu64 "\n", unwind_start_level);
                    fprintf(this->tracefile, "WARN: Last instr was\n");
                    this->last_instr->printMeFile(this->tracefile, std::string("WARN: "));
                }
            } else {
                LabelMeta * new_label = new LabelMeta();
                new_label->label = label;
                new_label->start_cycle = cycle;
                new_label->end_cycle = cycle;
                new_label->indent = label_stack.size() + 1;
                new_label->asm_sequence = this_instr->in_asm_sequence;
                label_stack.push_back(new_label);
                new_label->pre_print(this->tracefile);
            }
        }
        this->last_instr = this_instr;
    }
}


#ifdef TRACERV_TOP_MAIN

#define ENTRIES_PER_ROW 8

int main(int argc, char *argv[]) {
    uint64_t row[ENTRIES_PER_ROW];
    const uint64_t valid_mask = (1ULL << 40);
    std::string line;

    if (argc < 4) {
        fprintf(stderr, "Usage: %s <tracefile> <bindwarf> <uartlog> [modules ...]\n", argv[0]);
        exit(EXIT_FAILURE);
    }

    std::string tracefile = std::string(argv[1]);
    std::string bindwarf = std::string(argv[2]);
    std::string uartlog = std::string(argv[3]);
    std::vector<std::string> moddwarfs(argv + 4, argv + argc);

    std::ifstream uls(uartlog);
    const std::regex modre("(.*) [0-9]* 0 - Live 0x([0-9a-f]*) \\(O\\)");
    std::smatch modmatch;
    std::map<std::string, uint64_t> modbasemap;

    while (!uls.eof()) {
        getline(uls, line);
        auto lastpos = line.find('\r');
        if (lastpos != std::string::npos)
            line.erase(lastpos);

        if (std::regex_match(line, modmatch, modre)) {
            auto name = modmatch[1].str();
            uint64_t addr = strtoull(modmatch[2].str().c_str(), NULL, 16);
            printf("%s -> %" PRIx64 "\n", name.c_str(), addr);
            modbasemap[name] = addr;
        }
    }
    uls.close();

    TraceTracker *t = new TraceTracker(bindwarf, stdout, modbasemap, moddwarfs);

    FILE *tf = fopen(tracefile.c_str(), "r");
    if (tf == NULL) {
        perror("fopen");
        abort();
    }

    while (fread(row, sizeof(uint64_t), ENTRIES_PER_ROW, tf) == ENTRIES_PER_ROW) {
        uint64_t cycle = row[0];

        for (int i = 1; i < ENTRIES_PER_ROW; i++) {
            uint64_t addr = (row[i] << 24) >> 24;
            if (row[i] & valid_mask) {
                t->addInstruction(addr, cycle);
            }
        }
    }

    fclose(tf);
}

TraceTracker::~TraceTracker()
{
    delete bin_dump;
    for (auto dump : mod_dumps) { delete dump; }
}
#endif
