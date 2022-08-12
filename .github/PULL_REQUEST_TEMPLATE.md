<!-- 
First, please ensure that the title of your PR is sufficient to include in the next changelog.
Refer to https://github.com/firesim/firesim/releases for examples and feel free to ask reviewers for help.

Then, make sure to label your PR with one of the changelog:<section> labels to indicate which section
of the changelog should contain this PR's title:
  changelog:added
  changelog:changed
  changelog:fixed
  changelog:removed

If you feel that this PR should not be included in the changelog, you must still label it with
changelog:omit

Provide a brief description of the PR immediately below this comment, if the title is insufficient -->

#### Related PRs / Issues

<!-- List any related issues here -->

#### UI / API Impact

<!-- Roughly, how would this affect the current API or user-facing interfaces? (extend, deprecate, remove, or break) -->
<!-- Of note: manager config.ini interface, targetutils & bridge scala API, platform config behavior -->

#### Verilog / AGFI Compatibility

<!-- Does this change the generated Verilog or the simulator memory map of the default targets?  -->

### Contributor Checklist
- [ ] Is this PR's title suitable for inclusion in the changelog and have you added a `changelog:<topic>` label?
- [ ] Did you add Scaladoc/docstring/doxygen to every public function/method?
- [ ] Did you add at least one test demonstrating the PR?
- [ ] Did you delete any extraneous prints/debugging code?
- [ ] Did you state the UI / API impact?
- [ ] Did you specify the Verilog / AGFI compatibility impact?
<!-- Do this if this PR changes verilog or breaks the default AGFIs -->
- [ ] If applicable, did you regenerate and publicly share default AGFIs?
<!--
  CI will check linux boot on default targets, when the <ci:fpga-deploy> label is applied. Do this on:
  - Chipyard bumps / AGFIs updates / RTL or Driver changes affecting default targets.
  - If in doubt request a deployment, or ask another developer.

  NB: This *label* should be applied before the PR is created, or the branch
  will need to be resychronized to trigger a new CI workflow with the FPGA-deployment jobs.
-->
- [ ] If applicable, did you apply the `ci:fpga-deploy` label?
<!-- Do this if this PR is a bugfix that should be applied to the latest release -->
- [ ] If applicable, did you apply the `Please Backport` label?

### Reviewer Checklist (only modified by reviewer)
Note: to run CI on PRs from forks, comment `@Mergifyio copy main` and manage the change from the new PR.
- [ ] Is the title suitable for inclusion in the changelog and does the PR have a `changelog:<topic>` label?
- [ ] Did you mark the proper release milestone?
- [ ] Did you check whether all relevant Contributor checkboxes have been checked?
