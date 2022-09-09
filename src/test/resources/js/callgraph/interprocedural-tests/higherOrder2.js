var foo = function(g, x) {
    return g(x);
}

var bar = function(y) {
    return y;
}

var baz = function(z) {
    return 3;
}

var obj = {};

var valueToQuery;
if (true) {
    valueToQuery = foo(bar, obj);
} else {
    valueToQuery = foo(baz, bar);
}