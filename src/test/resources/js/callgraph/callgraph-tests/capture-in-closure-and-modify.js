function captureInClosureAndModify(func1, func2) {
    var x = func1;
    var closure = function clos() {
        x(); // callees: 17
        // closure handling currently loses flow sensitivity
    };
    x = func2;
    return closure;
}

var returnedClosure = captureInClosureAndModify(foo, bar);
returnedClosure();


function foo() {} // callers: 4
// overapproximation
function bar() {} // callers: 4
