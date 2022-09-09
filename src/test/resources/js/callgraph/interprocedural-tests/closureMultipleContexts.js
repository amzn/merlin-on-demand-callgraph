function outer(b) {
    function x() {
        var valueToQuery = b;
    }
    return x;
}

var obj1 = {};
var closure1 = outer(obj1);

var obj2 = {};
var closure2 = outer(obj2);

closure1();
closure2();