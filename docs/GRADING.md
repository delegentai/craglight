# Native grade notation and filtering

CragLight orders grades only within the selected source system. It does not convert UK trad, UK technical, French sport, V or Font grades into each other. The source notation remains visible.

The open UK catalogue contains V modifiers such as `VB-`, `V0-`, `V0+`, `V3+` and `V8+`. These are distinct ordinal positions: for example `V0- < V0 < V0+ < V1- < V1`. They are not rounded to whole grades. A maximum of `V0` therefore excludes `V0+`; a minimum of `V0` excludes `V0-`.

Fully written, adjacent source ranges such as `V4/V5` and `6a/6a+` retain both inclusive endpoints. A route matches when its interval overlaps the requested filter interval. Thus `V4/V5` appears in either an exact V4 or exact V5 search, with its range still displayed; it does not appear in an exact V6 search. Aggregate crag grade bands also retain the high endpoint of a source range. Reversed, non-adjacent, abbreviated or mixed-system slash notation is rejected rather than guessed.

French low grades `3a`, `3b`, `3c` and their plus variants are accepted in their native ordering below `4a`. The single observed UK technical bare `3` has no letter precision: it is treated as the broad 3a–3c category, retained as `3` on screen. This is an explicit uncertainty rule, not a claim that a source supplied a particular letter. Unrecognised technical bare numbers remain unknown.

The `rank` API is an internal ordinal lower edge, not a numerical measure of difficulty or a conversion score. Range matching uses both interval endpoints, not just this rank. Filter selectors continue to show native labels rather than converting or relabelling source grades.

This behaviour was added after auditing actual redistributed OSM values, including V modifiers and adjacent ranges, French low grades and adjacent ranges, and a bare UK technical grade. Four targeted JVM tests cover modifiers, interval intersections, rejected guesses and uncertainty. The release test report records their execution result.
