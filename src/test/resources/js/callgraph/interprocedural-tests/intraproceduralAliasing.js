var allocToTrack = {};
var x = {};
var y = x;
x.f = allocToTrack;
var readingX = x.f;
var readingY = y.f;