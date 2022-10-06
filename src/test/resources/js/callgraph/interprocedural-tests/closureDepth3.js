function outer(a) {
    function middle(b) {
        var c = {};
        function inner() {
            if (a) {
                return a;
            } else if (b) {
                return b;
            } else {
                return c;
            }
        }
        return inner;
    }
    return middle;
}

var x = {};
var y = {};
var cl_middle = outer(x);
var cl_inner = cl_middle(y);
var res = cl_inner();