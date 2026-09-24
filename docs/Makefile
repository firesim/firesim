# Minimal makefile for Sphinx documentation
#

# You can set these variables from the command line.
SPHINXOPTS    = -w warnings.txt -n
SPHINXBUILD   = python3 -msphinx
SPHINXPROJ    = FireSim
SOURCEDIR     = .
BUILDDIR      = _build

# Put it first so that "make" without argument is like "make help".
help:
	@$(SPHINXBUILD) -M help "$(SOURCEDIR)" "$(BUILDDIR)" $(SPHINXOPTS) $(O)

clean:
	rm -rf _build dist .astro public/sphinx src/content/docs
	find public -name '*.html' -delete
	mkdir -p src/content/docs
	touch src/content/docs/.gitkeep

format:
	find $(SOURCEDIR) \( -name node_modules -o -name _build -o -name dist -o -name .astro -o -name public -o -name src \) -prune -o -name '*.rst' -print | xargs docstrfmt $(FORMAT_EXTRA_ARGS) -i -v

formatcheck: FORMAT_EXTRA_ARGS := --check
formatcheck: format

.PHONY: help Makefile clean format formatcheck

# Catch-all target: route all unknown targets to Sphinx using the new
# "make mode" option.  $(O) is meant as a shortcut for $(SPHINXOPTS).
%: Makefile
	@$(SPHINXBUILD) -M $@ "$(SOURCEDIR)" "$(BUILDDIR)" $(SPHINXOPTS) $(O)
