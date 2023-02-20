function foo() { // callers: 5, 17

}

foo(); // callees: 1

function bar() { // callers: 17
}

var x;
if (unknown()) {
    x = foo;
} else {
    x = bar;
}

x(); // callees: 1, 7

function baz() {

}

var obj = {};
obj.f = baz;
var func = obj.f;
func(); // callees: 19
