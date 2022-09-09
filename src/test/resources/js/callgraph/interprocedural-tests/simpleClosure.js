var b = {}; // Should be in points-to set
function outer(c) {
    function x() {
        var valueToQuery = c;
    }
    return x;
}

var y = outer(b);
y();