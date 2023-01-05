# See LICENSE for license details.


################################################################################
# clang-tidy
################################################################################

testchipip_csrc_dir = $(chipyard_dir)/generators/testchipip/src/main/resources/testchipip/csrc

clang_tidy_files := $(shell \
	find $(firesim_base_dir) -name '*.cc' -or -name '*.h' \
		| grep -v generic_vharness.cc \
		| grep -v TestPointerChaser.cc \
		| grep -v simif_ \
		| grep -v tracerv \
		| grep -v dromajo \
		| grep -v serial \
		| grep -v fesvr \
		| grep -v generated-src \
		| grep -v output \
		| grep -v -F 'main.cc' \
)

clang_tidy_flags :=\
	-I$(firesim_base_dir)/midas/src/main/cc \
	-I$(firesim_base_dir)/firesim-lib/src/main/cc \
	-I$(testchipip_csrc_dir) \
	-std=c++17 \
	-x c++

# Checks the files in parallel without applying fixes.
.PHONY: clang-tidy
clang-tidy:
	@echo $(clang_tidy_files) \
		| tr ' ' '\n' \
	 	| parallel -I% --max-args 1 clang-tidy % -- $(clang_tidy_flags)


# Applies fixes to issues detected.
.PHONY: clang-tidy-fix
clang-tidy-fix:
	@clang-tidy -fix $(clang_tidy_files) -- $(clang_tidy_flags)
