This module manages the buildroot-based distribution for FireMarshal. In most
cases, you should not need to interact with this directly (it gets called from
the wlutil library directly).

= Customizing Buildroot =
If you choose to customize buildroot (change options, tweak packages, change
the buildroot overlay), you must run 'make' in this directory. Wlutil cannot
detect changes to buildroot-specific configurations.
