M8.0 fullscreen orientation fixture.

The supported composite, composite1, and final stages use an asymmetric corner
marker. composite2 deliberately uses GLSL ES, which is not a desktop version, to model a middle
stage fallback. Static checks require every executable schedule to use the same
fullscreen coordinate contract.
