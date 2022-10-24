
function foo(arg) {
    function bar() {
        var valueToQuery = arg;
        return valueToQuery;
    }
    return bar;
}

var obj = {};
var obj2 = {};
var callback = foo(obj);
var result = callback();
