function foo() {} // callers: 6

function captureInNestedClosure(func) {
    function outerClosure() {
        function innerClosure() {
            func(); // callees: 1
        }
        innerClosure();
    }
    return outerClosure;
}

var outerClos = captureInNestedClosure(foo);
outerClos();
