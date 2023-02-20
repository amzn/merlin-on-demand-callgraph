function invokeFieldOfObject(obj) {
    var func = obj.field;
    func(); // callees: 10
}

var y = {};
y.field = bar;
invokeFieldOfObject(y);

function bar() {} // callers: 3
