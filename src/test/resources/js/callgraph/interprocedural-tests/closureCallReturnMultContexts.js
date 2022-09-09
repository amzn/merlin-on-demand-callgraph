function outer(b) {
    function x() {
        return b;
    }
    return x;
}

var obj1 = {};
var closure1 = outer(obj1);

var obj2 = {};
var closure2 = outer(obj2);

var valueToQuery1 = closure1(); // should point to alloc on line 8
var valueToQuery2 = closure2(); // should point to alloc on line 11