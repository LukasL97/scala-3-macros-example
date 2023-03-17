# Macro annotations in Scala 3

In a previous blog post we took a look at macro annotations in Scala 2, where they have been present for a while.
Only recently they have been added to Scala 3 as well, specifically in the pre-release version 
[3.3.0-RC2](https://github.com/lampepfl/dotty/releases/tag/3.3.0-RC2) of the Dotty compiler.
Same as in Scala 2, macro annotations in Scala 3 allow us to transform the *abstract syntax tree (AST)* 
of a definition (e.g. a class or method) at compile time, allowing us to reduce boilerplate code, 
as the compiler generates it for us.

Macro annotations in Scala 3 differ from Scala 2 macro annotations with regard to when they are expanded during compilation.
While in Scala 2, macro annotations are expanded *before type-checking the AST*, in Scala 3, they are expanded
*after type-checking*.
This decision was made intentionally by the language designers, in order to ensure safety and robustness and
improve IDE support. [[1]](https://www.scala-lang.org/blog/2018/04/30/in-a-nutshell.html)

However, this decision makes the implementation of Scala 3 macro annotations currently more verbose.
In Scala 2, we could mostly write Scala code in quasiquotes and use it as output of our macro implementation.
Meanwhile, in Scala 3, we additionally have to take care of symbols, unique identifiers of definitions,
which especially makes adding new members to a class more complicated than in Scala 2.

In the blog post on Scala 2 macro annotations, we implemented a simple macro annotation for caching return values of
arbitrary methods as an example of how macro annotations can be used to reduce boilerplate code by generating it at
compile time. 
In this post, we will transfer this example to Scala 3, with some limitations.

## Caching method return values with macro annotations

Our goal is to implement a macro annotation that we can use to annotate methods.
If an annotated method is called with some input parameters, the method will first look them up in a dedicated cache
and return the corresponding value if the input is found in the cache.
In case of a cache miss, the method body is executed as normally and the result returned, but the result is also stored
in the cache, in order to retrieve it from there if the method is invoked with the same input again.
The entire code for this example can be found [here](https://github.com/LukasL97/scala-3-macros-example).

First, we define a `Cache` trait with a simple `MapCache` implementation (which is identical to the Scala 2 example):

```scala
trait Cache[K, V] {
  def put(key: K, value: V): Option[V]
  def get(key: K): Option[V]
}

class MapCache[K, V] extends Cache[K, V] {
  private val map = mutable.Map.empty[K, V]

  override def put(key: K, value: V): Option[V] = map.put(key, value)
  override def get(key: K): Option[V] = map.get(key)
}
```

In Scala 3, a macro annotation is defined as a subclass of the newly added `MacroAnnotation` class.
Currently, the class has to be annotated as `@experimental`, as the `MacroAnnotation` is still an experimental
feature in version 3.3.0-RC2 of the language.
The class in which we use our macro annotation has to be annotated as `@experimental` as well.
We define our `cached` annotation as subclass of `MacroAnnotation`:

```scala
@experimental
class cached extends MacroAnnotation {
  override def transform(using q: Quotes)(
    tree: quotes.reflect.Definition
  ): List[quotes.reflect.Definition] = ???
}
```

The `MacroAnnotation` offers a `transform` method which we override to define our macro transformation.
The `transform` method can return multiple definitions, allowing to add more members to the enclosing class,
which we will make use of to add a dedicated cache for the annotated method.
It has an additional implicit parameter `q` of type `Quotes`, which provides programmatic
access to the reflection API of Scala 3, similar to the `Context` in Scala 2.

Our implementation of the `transform` method transforms the body of the annotated method according to the
previously mentioned approach for reusing method return values.
Additionally, it generates the cache, which is referenced in the new method body and added as member to the enclosing
class.
Let's have a look at the implementation.
A step-by-step explanation is given below.

```scala
override def transform(using q: Quotes)(
  tree: quotes.reflect.Definition
): List[quotes.reflect.Definition] = {
  import q.reflect._

  tree match {
    case DefDef(name, params, returnType, Some(rhs)) =>                                // (1)

      val flattenedParams = params.map(_.params).flatten                               // (2)
      val paramTermRefs = flattenedParams.map(
        _.asInstanceOf[ValDef].symbol.termRef)
      val paramTuple = Expr.ofTupleFromSeq(paramTermRefs.map(Ident(_).asExpr))
        
      (paramTuple, rhs.asExpr) match {
        case ('{ $p: paramTupleType }, '{ $r: rhsType }) =>                            // (3)

          val cacheName = Symbol.freshName(name + "Cache")                             // (4)
          val cacheType = TypeRepr.of[Cache[paramTupleType, rhsType]]
          val cacheRhs = '{ new MapCache[paramTupleType, rhsType] }.asTerm
          val cacheSymbol = Symbol.newVal(
            tree.symbol.owner, cacheName, cacheType, Flags.Private, Symbol.noSymbol)
          val cache = ValDef(cacheSymbol, Some(cacheRhs))
          val cacheRef = Ref(cacheSymbol).asExprOf[Cache[paramTupleType, rhsType]]

          def buildNewRhs(using q: Quotes) = {                                         // (5)
            import q.reflect._
            '{
              val key = ${ paramTuple.asExprOf[paramTupleType] }
              $cacheRef.get(key) match {
                case Some(value) =>
                  value
                case None =>
                  val result = ${ rhs.asExprOf[rhsType] }
                  $cacheRef.put(key, result)
                  result
              }
            }
          }
          val newRhs = buildNewRhs(using tree.symbol.asQuotes).asTerm
          
          val expandedMethod = DefDef.copy(tree)(
            name, params, returnType, Some(newRhs))                                    // (6)

          List(cache, expandedMethod)
      }
    case _ =>
      report.error("Annottee must be a method")                                        // (7)
      List(tree)
  }
}
```

Starting in (1), the input AST is matched against a `DefDef` which is the definition of a method.
If the annottee is not a method, we report a compilation error in (7).

We first process the parameters of the method in (2), mapping them to an expression that represents
a tuple of the input parameters.
In Scala 2, we could just do this using a dedicated quasiquotes notation.
Now, we have to extract the symbols from the parameter definitions and build references to their identifiers.
The method `Expr.ofTupleFromSeq` allows us to build a tuple expression from a list of expressions.

In (3), we pattern match the parameter tuple expression and the method's right-hand side against *quotes*.
Quotes are conceptually similar to quasiquotes in Scala 2.
They are written as `'{ ... }` and can contain any typed Scala code.
In this case, we use them to extract the types of the parameter tuple and the method body, which we will use
as key and value type parameters of the cache.

We then build the cache definition in (4), starting with a fresh name.
We construct a type representation of the `Cache` type, with the key and value type parameters we extracted in (3).
The right-hand side of the cache definition is written as a quote in which we create a `MapCache`.
Then, we create the `cacheSymbol` as a symbol representing a `val`.
As parent we use the owner of the expanded method, i.e., the class in which the method is defined.
We then create the `cache` as `ValDef`, the definition of a `val`, using the symbol and right-hand side we
just created.
The `cache` will later be returned alongside the transformed input method.
We also need an additional reference to the `cacheSymbol`, in order to access the new cache definition inside
the transformed body of the annotated method.
In Scala 2, it was possible to just use the term name in a quasiquote.
Scala 3 however does require a `Ref` to the symbol explicitly.

The transformation of the method body happens in (5).
We use a helper method `buildNewRhs` to which we pass the symbol of the original method `asQuotes`.
This is necessary in order to ensure that any definitions created in the quote that describes our new
right-hand side are owned by the method.
Otherwise, the definitions inside the new right-hand side would be owned by the enclosing class and the
code would not compile.

The quote in `buildNewRhs` contains the transformed method body, which is analogous to the Scala 2 implementation.
The reference to the cache is inserted into the quote as `$cacheRef`, which is a *splice*.
Splices allow us to insert expressions into quoted code blocks, evaluating them before the surrounding code.
Similarly, we insert the `paramTuple` and the original `rhs` into the quote.
For the code to type-check, we have to cast them explicitly to expressions of the expected types.

It has to be noted, that we did not use fresh names for the `key` and `result` definitions in the new
method body.
This would of course still be possible and require a similar approach as with the `cacheRef`, creating
a new symbol for each and then references to these symbols, which can then be used inside the quoted code block.
For simplicity, this is omitted here, and we confine ourselves to using the static names `key` and `result`,
which could potentially have naming conflicts to method parameters.

Finally, in (6) we create the `expandedMethod` as a copy of the input method, replacing the original
right-hand side with the `newRhs`.
Then, we return it alongside the `cache`.

Now, we can use the annotation to annotate arbitrary methods as `@cached`.
The compiler will transform the annotated methods each according to the macro transformation defined above.
Additionally, it will add a dedicated `Cache` value for each annotated method as a member of the enclosing class.
As an example, two annotated methods with different method signatures, both using the `@cached` annotation, could look
as follows:

```scala
@cached
def f(x: Int, y: Int): Int = x * y

@cached
def g(x: Int, s: String): String = x.toString + s
```

## Conclusion

The caching example shows us that, even though they are in an experimental state, macro annotations in Scala 3 can 
already be used to reduce boilerplate code.

Compared to their usage in Scala 2, macro annotations in Scala 3 are more verbose, which is due to the fact that they
work with typed ASTs, compared to untyped ASTs in Scala 2.
Hence, we have to take care of proper usage of symbols and references, which makes especially the definition of the
cache more laborious than in Scala 2.

Further, it is no longer possible to use implicit resolution to pass a value from application
code into the code generated by the macro.
This is due to the fact that macro expansion now happens after implicits are resolved by the compiler.
An `implicitly` in macro code will not be resolved from an implicit (or `given`) value in application code.
In the Scala 2 implementation, we used this trick to make the cache implementation configurable,
i.e., to define an implementation other than the simple `MapCache` class in the application code and use it in the
code generated by the macro transformation.

Lastly, IDE support is still an issue in Scala 3, at least at the moment, using the Scala plugin of IntelliJ IDEA.
However, with macro annotations just having been added as experimental feature to Scala 3, there is some hope that
this might change in the future.
In this regard, quotes in Scala 3 are an improvement over quasiquotes in Scala 2,
as they are actually interpreted as Scala code by the IDE.

## References

[1] https://www.scala-lang.org/blog/2018/04/30/in-a-nutshell.html