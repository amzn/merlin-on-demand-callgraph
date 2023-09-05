function bar() {} // callers: 10

var obj1 = {};
var obj2 = {};
obj1.a = obj2;
obj2.b = bar;

var obj1a = obj1.a;
var func = obj1a.b;
func();
