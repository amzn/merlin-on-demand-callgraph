var x = "abc";
var y = "def";
var z = x + y; // points-to: 1, 2

function add(a, b) {
    return a + b;
}

var result = add(x, y); // points-to: 1, 2