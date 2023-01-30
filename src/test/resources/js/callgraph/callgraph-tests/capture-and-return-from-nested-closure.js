function foo() {} // callers: 16

function captureInNestedClosure(func) {
    function outerClosure() {
        function innerClosure() {
            return func;
        }
        return innerClosure;
    }
    return outerClosure;
}

var outerClos = captureInNestedClosure(foo);
var innerClos = outerClos();
var f = innerClos(); // callees: 5
f(); // callees: 1