function call(func) { // callers: 7
    func(); // callees: 9
}

var obj = {};
obj.f = call;
obj.f(foo); // callees: 1

function foo() {} // callers: 2

