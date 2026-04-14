package org.automerge

/** Factory for [ChangeHash] — the constructor is package-protected. */
object ChangeHashFactory {
    fun create(bytes: ByteArray): ChangeHash = ChangeHash(bytes)
}
