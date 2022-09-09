var b = {}; // Should be in points-to set
function outer() {
    function x() {
        var valueToQuery = b;
    }
    return x;
}

var y = outer();
y();
b = {}; // Should be in points-to set
y();