function bar() {} // callers: 9, 11

var obj1 = {};
if (something()) {
    obj1.f = bar;
} else {
    obj1 = bar;
}
obj1();
var fieldRead = obj1.f;
fieldRead();
