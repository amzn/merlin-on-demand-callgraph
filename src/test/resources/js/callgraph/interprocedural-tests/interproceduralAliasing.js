function setProp(obj, alloc) { // obj aliases with x
    obj.f = alloc;
}

var allocToTrack = {};
var x = {};
setProp(x, allocToTrack);
var readingFromX = x.f;