function captureInClosure(func) {
    return function() { // callers: 8
        func(); // callees: 10
    };
}

var capturedFunc = captureInClosure(bar);
capturedFunc();

function bar() {} // callers: 3
