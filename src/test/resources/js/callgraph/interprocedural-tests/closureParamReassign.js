function bar(x) {
    function foo() {
        var something = x;
        return something;
    }
    var res = foo();
    x = {};
    var res2 = foo();
    return res;
}

var obj = {};

var y = bar(obj);