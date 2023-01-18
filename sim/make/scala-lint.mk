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


.PHONY: scalaFix
scalaFix:
	cd $(base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		scalafixEnable; \
		firesim / scalafix; \
		firesimLib / scalafix; \
		midas / scalafix; \
		targetutils / scalafix; "

.PHONY: scalaFixCheck
scalaFixCheck:
	cd $(base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		scalafixEnable; \
		firesim / scalafix --check; \
		firesimLib / scalafix --check; \
		midas / scalafix --check; \
		targetutils / scalafix --check; "
