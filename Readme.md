# Scala 3 Macro Annotations Example

This is a simple example of macro annotations in Scala 3. 
The approach is described in [this blog post](https://www.codecentric.de/wissens-hub/blog/macro-annotations-in-scala-3).

The sbt project contains two subprojects:
* `macros` contains the macro annotation definition for the `@cached` annotation
* `examples` contains the example usage of the `@cached` annotation

The example can be run as follows:

```bash
sbt examples/run
```

The expected output is the following:

```
CACHE MISS for key (1,2)
2
CACHE HIT for key (1,2): 2
2
CACHE MISS for key (2,1)
2
CACHE MISS for key (3,x)
3x
CACHE HIT for key (3,x): 3x
3x
```

The cache logs a `CACHE MISS` when an input to the cached method is used for the first time.
If the input has been used before, the cache logs a `CACHE HIT` and returns the cached result.
