function Bird(colour, size, name) {
    this.colour = colour;
    this.size = size;
    this.name = name;
}

Bird.prototype.getSize = function(){
    return this.size;
}

var birdSize = "small"
var marsh_wren = new Bird("brown", birdSize, "Marsh Wren");

var valueToQuery = marsh_wren.getSize();