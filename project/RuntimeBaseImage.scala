import sbt.*

/** Reads the runtime base image reference from docker/runtime/Dockerfile.
  */
object RuntimeBaseImage {

  private val FromLine = """(?i)FROM\s+(\S+)\s*""".r

  /** The reference of the file's only `FROM` instruction, which must be pinned
    * by digest. Blank lines and `#` comments are ignored. The `Left` names the
    * file.
    */
  def read(file: File): Either[String, String] = {
    val froms = IO
      .readLines(file)
      .map(_.trim)
      .filterNot(line => line.isEmpty || line.startsWith("#"))
      .filter(_.toUpperCase.startsWith("FROM"))
    froms match {
      case Seq(FromLine(reference)) =>
        if (reference.contains("@sha256:")) Right(reference)
        else
          Left(s"$file: the FROM reference '$reference' has no @sha256: digest")
      case Seq(other) => Left(s"$file: cannot read the FROM line '$other'")
      case Seq()      => Left(s"$file: has no FROM line")
      case many       =>
        Left(s"$file: has ${many.size} FROM lines, expected exactly one")
    }
  }
}
