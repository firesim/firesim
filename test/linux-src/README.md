Test support for alternate linux source directories. In this case, we copy the
normal linux source tree and add a simple patch that includes an extra printk
during shutdown. A more typical usage might be to have a custom fork of linux
that you submodule in your workload directory.

= How the unit test works =
printk's get timestamps, which means the normal uartlog stripping and grepping
doesn't really play nice with this non-deterministic output. Instead, we
include a post\_run\_hook that greps for the correct outputs and touches a
"SUCCESS" file if it works. It touches "FAILURE" if it doesn't work (although
we don't actually check for that). The refOutput dir just checks for the
existence of this file.

It's likely that more complex workloads will create non-deterministic output
that is complex to validate. Adding an empty "SUCCESS" file is the typical way
to deal with that. It could be created during run-time or in a post\_run\_hook.
