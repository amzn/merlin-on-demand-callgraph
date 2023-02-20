# Propagating data flow through closures

## Forward flow functions

Propagating data flow through closures is tricky to combine with SPDS while
retaining flow sensitivity. The naive approach is to add a data flow edge into
the body of a function whenever a value flows into a variable captured by a
closure. However, this results in mismatched push and pop statements in the call
automaton as the closure function has not actually been invoked yet. To remedy
this, the following options come to mind:

1. Track the fact that the variable was captured in a closure as a data flow
fact add flow to captured variable when a call to the function capturing the
variable happens (i.e. the function flows to a call node). This approach has the
advantage that it preserves calling context sensitivity. However, it is tricky
to implement correctly and requires more expressive data flows. The interaction
with field sensitivity and termination may be unclear (e.g. if closure-captured
variables allow nesting somehow, the set of possible data flow facts may no
longer be finite).

Additionally, it is unclear how to handle multiple writes to closure-captured
variables.

2. Instead, we can locate the call sites of the declared function, and for each
call site, add a new flow from the initial query (i.e. the initial allocation we
are tracking) to the call site, and then another flow into the variable at the
start of the same function. This is done both when a write to a captured
variable occurs or when entering a the body of a function containing closures
capturing variables (since there may not be a write to the captured variable).

This approach, while simpler than (1), loses calling-context sensitivity (and
probably field sensitivity) since we ignore the path that led us to this
function declaration so far.
   
For simplicity and easier soundness arguments, we implement option 2 here.

## Backward flow functions

In the backwards case, we handle flows to captured variables when reaching the entry point of
a function. If the current query value is a variable bound outside of the current function, we
add a data flow edge to the point where the variable is captured rather than to call sites. 