package dev.zacsweers.fieldspottr.util

import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

val BufferedSource.lines: Sequence<String>
  get() = generateSequence(::readUtf8Line)

inline fun BufferedSource.useLines(body: (Sequence<String>) -> Unit) {
  use { body(lines) }
}

/** Simulates a `touch` command on a [path]. */
fun FileSystem.touch(path: Path) {
  path.parent?.let { createDirectories(it) }
  sink(path).buffer().use {}
}
