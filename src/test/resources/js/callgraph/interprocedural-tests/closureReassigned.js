var b = {}; // Should NOT be in points-to set: b gets reassigned before the first invocation of x
function outer() {
    function x() {
        var valueToQuery = b;
    }
    return x;
}

var y = outer();
b = {}; // Should be in points-to set
y();