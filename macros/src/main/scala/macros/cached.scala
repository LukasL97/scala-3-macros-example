package de.codecentric
package macros

import scala.annotation.{MacroAnnotation, experimental}
import scala.quoted.*

@experimental
class cached extends MacroAnnotation {
  override def transform(using q: Quotes)(tree: quotes.reflect.Definition): List[quotes.reflect.Definition] = {
    import q.reflect._

    tree match {
      case DefDef(name, params, returnType, Some(rhs)) =>

        val flattenedParams = params.map(_.params).flatten
        val paramTermRefs = flattenedParams.map(_.asInstanceOf[ValDef].symbol.termRef)
        val paramTuple = Expr.ofTupleFromSeq(paramTermRefs.map(Ident(_).asExpr))

        val cacheName = Symbol.freshName(name + "Cache")

        (paramTuple, rhs.asExpr) match {
          case ('{ $p: paramTupleType }, '{ $r: rhsType }) =>

            val cacheType = TypeRepr.of[Cache[paramTupleType, rhsType]]
            val cacheRhs = '{ new MapCache[paramTupleType, rhsType] }.asTerm
            val cacheSymbol = Symbol.newVal(tree.symbol.owner, cacheName, cacheType, Flags.Private, Symbol.noSymbol)
            val cache = ValDef(cacheSymbol, Some(cacheRhs))
            val cacheRef = Ref(cacheSymbol).asExprOf[Cache[paramTupleType, rhsType]]

            def buildNewRhs(using q: Quotes) = {
              import q.reflect._
              '{
                val key = ${ paramTuple.asExprOf[paramTupleType] }
                $cacheRef.get(key) match {
                  case Some(value) =>
                    println("CACHE HIT for key " + key + ": " + value)
                    value
                  case None =>
                    println("CACHE MISS for key " + key)
                    val result = ${ rhs.asExprOf[rhsType] }
                    $cacheRef.put(key, result)
                    result
                }
              }
            }

            val newRhs = buildNewRhs(using tree.symbol.asQuotes).asTerm
            val expandedMethod = DefDef.copy(tree)(name, params, returnType, Some(newRhs))

            List(cache, expandedMethod)
        }
      case _ =>
        report.error("Annottee must be a method")
        List(tree)
    }
  }
}
