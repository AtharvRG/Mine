# SUPERSEDED — do not cite

This is the **v1** D-124 loop. It archived whatever sat in the results dir with no staleness
guard, so a run whose UTP artifacts were empty silently inherited the previous run's logcat and
looked like a different failure.

Use `../d124-loop-v2/` and `../d124-loop-v2-report.md` instead. v2 records artifact mtimes and
the XML timestamp for every run, so a stale archive is visible rather than invisible.
