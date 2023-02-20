function invokeField(obj) {
    obj.field(); // callees: 11
}

var x = {
    field: foo
};

invokeField(x);

function foo() {} // callers: 2
