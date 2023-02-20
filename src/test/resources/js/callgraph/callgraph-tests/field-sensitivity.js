function foo() { // callers: 23

}

function bar() {} // callers: 15
function baz() {}

var obj1 = {};
var obj2 = {};
obj1.a = obj2;
obj1.a.a = bar;

var obj1a = obj1.a;
var func = obj1a.a;
func(); // callees: 5

var obj3 = {};
obj2.b = obj3;
obj3.a = foo;

var obj1ab = obj1a.b;
var anotherFunc = obj1ab.a;
anotherFunc(); // callees: 1