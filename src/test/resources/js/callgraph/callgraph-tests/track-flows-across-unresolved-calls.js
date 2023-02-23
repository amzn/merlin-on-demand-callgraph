var x = "abc";
var result = String(x); // points-to: 1


var x = id(foo); // id is undefined but the result should depend on foo
x();

function foo() {} // callers: 6