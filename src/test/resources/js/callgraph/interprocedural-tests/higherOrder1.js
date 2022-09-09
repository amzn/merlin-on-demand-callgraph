var foo = function(g, x) {
    return g(x);
}

var bar = function(y) {
    return y;
}

var obj = {};

var valueToQuery = foo(bar, obj);
