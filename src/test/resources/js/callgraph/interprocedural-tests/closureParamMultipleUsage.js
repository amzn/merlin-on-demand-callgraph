var b = {}; // Should be in points-to set
function outer(c) {
    function x() {
        var valueToQuery = c;
    }
    return x;
}

var y = outer(b);
y();
b = {}; // Should be in points-to set, but requires knowledge
        // of the aliasing between b and arg1 at line 9
        // on the IR level
y();