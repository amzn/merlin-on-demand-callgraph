var x = {};
var i = 0;
while (i < 10) {
    i = i + 1;
    var y = {};
    x.next = y;
    x = y;
}