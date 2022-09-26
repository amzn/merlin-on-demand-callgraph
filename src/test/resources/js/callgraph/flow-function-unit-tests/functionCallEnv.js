function identity(x) {
    y = otherObj;
    return x;
}

var obj = {};
var otherObj = {} // should be visible in identity()
var x = {}; // should NOT be visible in identity()

var valueToQuery = identity(obj);