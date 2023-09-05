function captureInClosure(func) {
    return function closure() { // callers: 8
        return func;
    };
}

var capturedFunc = captureInClosure(bar);
var resultOfClosure = capturedFunc();
resultOfClosure(); // callees: 11

function bar() {} // callers: 9
