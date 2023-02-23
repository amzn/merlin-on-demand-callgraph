var x = unresolved(foo);
var y = unresolved2(x);
y(); // callees: 5

function foo() {} // callers: 3
