function app(g, x) {
    return g(x);
}

function id(y) {
    return y;
}

var obj = {};

var valueToQuery = app(id, obj);
