trait Foo[-T]

trait Bar[-T] extends Foo[T]

object Test {
  implicit val fa: Foo[Any] = ???
  implicit val ba: Bar[Int] = ???

  def test: Unit = {
    implicitly[Foo[Int]]
  }
}
