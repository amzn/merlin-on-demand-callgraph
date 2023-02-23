var obj = new foo();
obj.field = bar;
obj.field(); // callees: 6

function foo() {}
function bar() {} // callers: 3

