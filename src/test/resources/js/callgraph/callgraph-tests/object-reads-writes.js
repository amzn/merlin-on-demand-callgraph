function bar() {} // callers: 10

var obj1 = {};
var obj2 = {};
obj1.a = obj2;
obj2.b = bar;

var obj1a = obj1.a;
var func = obj1a.b;
func();

function foo() {} // callers: 20
                  // ^ false positive due to lack of flow sensitivity
function bla() {} // callers: 20

var obj3 = {};
obj3.a = foo;
obj3.a = bla;
var fieldConts = obj3.a;
fieldConts();