// scalac: -language:experimental.macros
object Test extends App {
  val designator: Macros.type = Macros
  designator.foo(42)
}
