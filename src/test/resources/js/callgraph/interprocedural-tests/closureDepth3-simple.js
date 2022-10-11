function outer() {
    function middle(b) {
        function inner() {
            return b;
        }
        return inner;
    }
    return middle;
}

var y = {};
var cl_middle = outer();
var cl_inner = cl_middle(y);
var res = cl_inner();