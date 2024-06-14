package dev.zacsweers.fieldspottr.util

operator fun <E> List<E>.component6(): E {
  return this[5]
}

operator fun <E> List<E>.component7(): E {
  return this[6]
}

/** Analogous to `Objects.hash` on the JVM. */
fun hashOf(vararg inputs: Any): Int {
  var hash = 0
  for (v in inputs) {
    hash += v.hashCode()
    hash *= 31
  }
  return hash
}
