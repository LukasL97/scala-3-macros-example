package de.codecentric

import macros.cached

import scala.annotation.experimental

@experimental
object CachedExample extends App {

  @cached
  def f(x: Int, y: Int): Int = x * y

  @cached
  def g(x: Int, s: String): String = x.toString + s

  println(f(1, 2))
  println(f(1, 2))
  println(f(2, 1))

  println(g(3, "x"))
  println(g(3, "x"))

}
