function foo() { // callers: 10

}

function id(f) {
    return f;
}

var idfoo = id(foo);
idfoo(); // callees: 1
