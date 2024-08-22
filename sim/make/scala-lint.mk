# See LICENSE for license details.

#########################
# Scalafmt              #
#########################

# Checks that all scala main sources under firesim SBT subprojects are formatted.
.PHONY: scalafmtCheckAll
scalafmtCheckAll:
	cd $(firesim_base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		firesim / scalafmtCheckAll; \
		firesim / Test / scalafmtCheckAll; \
		firesimLib / scalafmtCheckAll; \
		firesimLib / Test / scalafmtCheckAll; \
		midas / scalafmtCheckAll; \
		midas / Test / scalafmtCheckAll; \
		targetutils / scalafmtCheckAll; \
		targetutils / Test / scalafmtAll;"

# Runs the code reformatter in all firesim SBT subprojects
.PHONY: scalafmtAll
scalafmtAll:
	cd $(firesim_base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		firesim / scalafmtAll; \
		firesim / Test / scalafmtAll; \
		firesimLib / scalafmtAll; \
		firesimLib / Test / scalafmtAll; \
		midas / scalafmtAll; \
		midas / Test / scalafmtAll; \
		targetutils / scalafmtAll; \
		targetutils / Test / scalafmtAll;"

# Checks scala sources comply with the Scalafix rules defined here: sim/.scalafix.conf
.PHONY: scalaFixCheck
scalaFixCheck:
	cd $(firesim_base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		firesim / scalafix --check; \
		firesim / Test / scalafix --check; \
		firesimLib / scalafix --check; \
		firesimLib / Test / scalafix --check; \
		midas / scalafix --check; \
		midas / Test / scalafix --check; \
		targetutils / scalafix --check; \
		targetutils / Test / scalafix --check;"

# Applies the scalafix rules defined here: sim/.scalafix.conf
.PHONY: scalaFix
scalaFix:
	cd $(firesim_base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		firesim / scalafix; \
		firesim / Test / scalafix; \
		firesimLib / scalafix; \
		firesimLib / Test / scalafix; \
		midas / scalafix; \
		midas / Test / scalafix; \
		targetutils / scalafix; \
		targetutils / Test / scalafix;"

# These targets combine Scalafix and Scalafmt passes.
# It's important to run Scalafix first so Scalafmt can cleanup whitespace issues
.PHONY: scala-lint scala-lint-check
scala-lint: scalaFix scalafmtAll
scala-lint-check: scalaFixCheck scalafmtCheckAll
