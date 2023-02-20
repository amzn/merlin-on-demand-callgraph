function invokeFieldOfObject(obj) {
    var func = obj.field;
    func(); // callees: 13
}
function invokeFieldIndirectly(obj) {
    invokeFieldOfObject(obj);
}

var x = {};
x.field = bar;
invokeFieldIndirectly(x);

function bar() {} // callers: 3
