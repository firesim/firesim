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
- [ ] (If applicable) Did you regenerate and publicly share default AGFIs?
<!-- Do this if this PR is a bugfix that should be applied to the latest release -->
- [ ] (If applicable) Did you mark the PR as "Please Backport"?

### Reviewer Checklist (only modified by reviewer)
- [ ] Is the title suitable for inclusion in the changelog and does the PR have a `changelog:<topic>` label?
- [ ] Did you mark the proper release milestone?
- [ ] Did you check whether all relevant Contributor checkboxes have been checked?
