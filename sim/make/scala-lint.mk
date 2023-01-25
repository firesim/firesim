# See LICENSE for license details.

#########################
# Scalafmt              #
#########################

# Checks that all scala main sources under firesim SBT subprojects are formatted.
.PHONY: scalafmtCheckAll
scalafmtCheckAll:
	cd $(base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		firesim / scalafmtCheckAll; \
		firesimLib / scalafmtCheckAll; \
		midas / scalafmtCheckAll ; \
		targetutils / scalafmtCheckAll ;"

# Runs the code reformatter in all firesim SBT subprojects
.PHONY: scalafmtAll
scalafmtAll:
	cd $(base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		firesim / scalafmtAll; \
		firesimLib / scalafmtAll; \
		midas / scalafmtAll ; \
		targetutils / scalafmtAll ;"

# Checks scala sources comply with the Scalafix rules defined here: sim/.scalafix.conf
.PHONY: scalaFixCheck
scalaFixCheck:
	cd $(base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		firesim / scalafix --check; \
		firesimLib / scalafix --check; \
		midas / scalafix --check; \
		targetutils / scalafix --check; "

# Applies the scalafix rules defined here: sim/.scalafix.conf
.PHONY: scalaFix
scalaFix:
	cd $(base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		firesim / scalafix; \
		firesimLib / scalafix; \
		midas / scalafix; \
		targetutils / scalafix; "

# These targets combine Scalafix and Scalafmt passes.
# It's important to run Scalafix first so Scalafmt can cleanup whitespace issues
.PHONY: scala-lint scala-lint-check
scala-lint: scalaFix scalafmtAll
scala-lint-check: scalaFixCheck scalafmtCheckAll
