/**
 * Compiles a raw bigram frequency file (e.g. Norvig's count_2w.txt) into a
 * compact binary format for the app's next-word prediction engine.
 *
 * Source: https://norvig.com/ngrams/count_2w.txt (MIT license)
 * Corpus: Google Web Trillion Word Corpus
 *
 * Run via Gradle:
 *   ./gradlew compileBigrams
 *
 * Or manually:
 *   cd tools/corpus
 *   curl -O https://norvig.com/ngrams/count_2w.txt
 *   kotlinc BigramCompiler.kt -include-runtime -d BigramCompiler.jar
 *   java -jar BigramCompiler.jar count_2w.txt ../../app/src/main/assets/bigrams.bin
 *
 * Binary format (big-endian):
 *   Header:
 *     [vocab_size: UInt16]  [bigram_count: UInt32]
 *   Vocab section (vocab_size entries, ordered by ID):
 *     [length: UInt8] [utf8_bytes...]
 *   Bigram section (bigram_count entries, sorted by w1_id then count desc):
 *     [w1_id: UInt16] [w2_id: UInt16] [count: UInt32]
 *
 * Bigrams are sorted by w1_id so the runtime predictor can binary-search
 * to find all continuations for a given word in O(log n).
 */

import java.io.File
import java.io.DataOutputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream

fun main(args: Array<String>) {
    val inputPath = args.getOrNull(0) ?: "count_2w.txt"
    val outputPath = args.getOrNull(1) ?: "bigrams.bin"
    val inputFile = File(inputPath)
    val outputFile = File(outputPath)

    if (!inputFile.exists()) {
        System.err.println("Error: ${inputFile.path} not found.")
        System.err.println("Download: curl -o ${inputFile.path} https://norvig.com/ngrams/count_2w.txt")
        System.exit(1)
    }

    val maxBigrams = 50_000
    val alphaRegex = Regex("^[a-z]+$")

    // Parse and filter
    println("Reading ${inputFile.name}...")
    val bigrams = mutableListOf<Triple<String, String, Long>>()
    var totalLines = 0
    var skipped = 0

    inputFile.forEachLine { line ->
        totalLines++
        val tabIdx = line.lastIndexOf('\t')
        if (tabIdx < 0) { skipped++; return@forEachLine }

        val wordsPart = line.substring(0, tabIdx)
        val countStr = line.substring(tabIdx + 1)
        val spaceIdx = wordsPart.indexOf(' ')
        if (spaceIdx < 0) { skipped++; return@forEachLine }

        val w1 = wordsPart.substring(0, spaceIdx)
        val w2 = wordsPart.substring(spaceIdx + 1)

        if (!alphaRegex.matches(w1) || !alphaRegex.matches(w2)) {
            skipped++
            return@forEachLine
        }

        val count = countStr.toLongOrNull()
        if (count == null) { skipped++; return@forEachLine }

        bigrams.add(Triple(w1, w2, count))
    }

    println("  Total lines: $totalLines")
    println("  Alphabetic bigrams: ${bigrams.size}")
    println("  Skipped: $skipped")

    // Sort by count descending, take top N
    bigrams.sortByDescending { it.third }
    val top = bigrams.take(maxBigrams)
    println("  Top $maxBigrams count range: ${top.first().third} .. ${top.last().third}")

    // Build vocabulary
    val vocab = LinkedHashMap<String, Int>()
    for ((w1, w2, _) in top) {
        if (w1 !in vocab) vocab[w1] = vocab.size
        if (w2 !in vocab) vocab[w2] = vocab.size
    }
    println("  Unique words: ${vocab.size}")

    // Index bigrams and sort by (w1_id, -count) for binary search at runtime
    data class IndexedBigram(val w1Id: Int, val w2Id: Int, val count: Long)
    val indexed = top.map { (w1, w2, c) -> IndexedBigram(vocab[w1]!!, vocab[w2]!!, c) }
        .sortedWith(compareBy<IndexedBigram> { it.w1Id }.thenByDescending { it.count })

    // Write binary
    outputFile.parentFile?.mkdirs()

    DataOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { out ->
        out.writeShort(vocab.size)
        out.writeInt(indexed.size)

        for (word in vocab.keys) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            out.writeByte(bytes.size)
            out.write(bytes)
        }

        for (b in indexed) {
            out.writeShort(b.w1Id)
            out.writeShort(b.w2Id)
            out.writeInt(b.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    val sizeKb = outputFile.length() / 1024
    println("\nWritten: ${outputFile.path} ($sizeKb KB)")
    println("  Vocab: ${vocab.size} words")
    println("  Bigrams: ${indexed.size} entries")

    println("\nTop 10:")
    val wordById = vocab.entries.associate { it.value to it.key }
    for (b in indexed.take(10)) {
        println("  ${wordById[b.w1Id]} ${wordById[b.w2Id]}: ${b.count}")
    }
}
