package satisfactory.runner

/**
 * Dataset content never reaches a log (spec FR-069a): a line that looks like it carries JSON, or is
 * long enough to be a body, is replaced by what kind of thing it was. Exception messages from JSON
 * mapping quote the input they choked on, which is exactly what this is for.
 */
object LogHygiene:
  val MaxLength = 512

  def clean(line: String): String =
    if line.length > MaxLength || line.exists(c => c == '{' || c == '[' || c == '"') then
      s"[redacted: ${line.length} characters that may quote the dataset]"
    else line
