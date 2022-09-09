var b = {}; // Should be in points-to set
function outer() {
    function x() {
        var valueToQuery = b;
    }
    return x;
}

var y = outer();

function newScope() {
    var b = {}; // Should NOT be in points-to set: this is not the same b as on line 4
    y();
}

newScope();