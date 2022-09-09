var x = {a: 5, b: 7};
with (x) {
    a = 3;
}
var y = x.a;

var z = eval("y + x.b");