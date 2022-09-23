var getP = function(x) {
    return x.p;
}

var y = {};
var z = {};
y.p = z;
var valueToQuery = getP(y);