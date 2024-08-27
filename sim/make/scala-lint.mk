# See LICENSE for license details.

#########################
# Scalafmt              #
#########################

.PHONY: scalafmt
scalafmt:
	cd $(firesim_base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		firesim / $(SCALAFMT_CMD); \
		firesim / Test / $(SCALAFMT_CMD); \
		firesimLib / $(SCALAFMT_CMD); \
		firesimLib / Test / $(SCALAFMT_CMD); \
		midas / $(SCALAFMT_CMD); \
		midas / Test / $(SCALAFMT_CMD); \
		targetutils / $(SCALAFMT_CMD); \
		targetutils / Test / $(SCALAFMT_CMD);"

# Checks that all scala main sources under firesim SBT subprojects are formatted.
.PHONY: scalafmtCheckAll
scalafmtCheckAll: SCALAFMT_CMD := scalafmtCheckAll
scalafmtCheckAll: scalafmt

# Runs the code reformatter in all firesim SBT subprojects
.PHONY: scalafmtAll
scalafmtAll: SCALAFMT_CMD := scalafmtAll
scalafmtAll: scalafmt

# Applies the scalafix rules defined here: sim/.scalafix.conf
.PHONY: scalaFix
scalaFix:
	cd $(firesim_base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		firesim / scalafix $(SCALAFIX_EXTRA_ARGS); \
		firesim / Test / scalafix $(SCALAFIX_EXTRA_ARGS) ; \
		firesimLib / scalafix $(SCALAFIX_EXTRA_ARGS); \
		firesimLib / Test / scalafix $(SCALAFIX_EXTRA_ARGS); \
		midas / scalafix $(SCALAFIX_EXTRA_ARGS); \
		midas / Test / scalafix $(SCALAFIX_EXTRA_ARGS); \
		targetutils / scalafix $(SCALAFIX_EXTRA_ARGS); \
		targetutils / Test / scalafix $(SCALAFIX_EXTRA_ARGS);"

# Checks scala sources comply with the Scalafix rules defined here: sim/.scalafix.conf
.PHONY: scalaFixCheck
scalaFixCheck: SCALAFIX_EXTRA_ARGS := --check
scalaFixCheck: scalaFix

# These targets combine Scalafix and Scalafmt passes.
# It's important to run Scalafix first so Scalafmt can cleanup whitespace issues
.PHONY: scala-lint scala-lint-check
scala-lint: scalaFix scalafmtAll
scala-lint-check: scalaFixCheck scalafmtCheckAll
