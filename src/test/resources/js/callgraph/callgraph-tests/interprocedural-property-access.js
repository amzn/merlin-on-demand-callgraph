function setStaticField(obj, value) {
    obj.field = value;
}

var x = {};
setStaticField(x, foo);
var func = x.field;
func(); // callees: 10

function foo() {} // callers: 8

function invokeFieldOfObject(obj) {
    var func = obj.field;
    func(); // callees: 21
}

var y = {};
y.field = bar;
invokeFieldOfObject(y);

function bar() {} // callers: 14