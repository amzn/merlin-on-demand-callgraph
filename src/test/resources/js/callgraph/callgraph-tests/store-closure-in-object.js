function returnFromClosure(func) {
    return function() { // callers: 15
        return func;
    };
}

function storeInObj(obj, value) {
    obj.field = value;
}

var capturedFunc = returnFromClosure(bar);
var obj = {};
storeInObj(obj, capturedFunc); // callees: 7
var readFromObj = obj.field;
var closureResult = readFromObj();
closureResult(); // callees: 18

function bar() {} // callers: 16
