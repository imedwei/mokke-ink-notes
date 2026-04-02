// Compile bigram frequency data from Norvig's count_2w.txt into a compact binary
// for the next-word prediction engine. Run: ./gradlew compileBigrams
// Source: https://norvig.com/ngrams/count_2w.txt (MIT license, Google Web Trillion Word Corpus)
//
// Binary format (big-endian):
//   Header:  [vocab_size: UInt16] [bigram_count: UInt32]
//   Vocab:   [length: UInt8] [utf8_bytes...]  (vocab_size entries)
//   Bigrams: [w1_id: UInt16] [w2_id: UInt16] [count: UInt32]  (sorted by w1_id, then count desc)

tasks.register("compileBigrams") {
    description = "Compile bigram frequency table from tools/corpus/count_2w.txt → assets/bigrams.bin"
    group = "build"
    val inputFile = rootProject.file("tools/corpus/count_2w.txt")
    val outputFile = project.file("src/main/assets/bigrams.bin")
    inputs.file(inputFile)
    outputs.file(outputFile)

    doLast {
        if (!inputFile.exists()) {
            throw GradleException(
                "Bigram source not found: ${inputFile.path}\n" +
                "Download it: curl -o tools/corpus/count_2w.txt https://norvig.com/ngrams/count_2w.txt"
            )
        }

        val maxBigrams = 50_000
        val alphaRegex = Regex("^[a-z]+$")

        // Parse: each entry is [w1, w2, count]
        val bigrams = mutableListOf<Triple<String, String, Long>>()
        var skipped = 0

        inputFile.forEachLine { line ->
            val tabIdx = line.lastIndexOf('\t')
            if (tabIdx < 0) { skipped++; return@forEachLine }
            val wordsPart = line.substring(0, tabIdx)
            val countStr = line.substring(tabIdx + 1)
            val spaceIdx = wordsPart.indexOf(' ')
            if (spaceIdx < 0) { skipped++; return@forEachLine }
            val w1 = wordsPart.substring(0, spaceIdx)
            val w2 = wordsPart.substring(spaceIdx + 1)
            if (!alphaRegex.matches(w1) || !alphaRegex.matches(w2)) { skipped++; return@forEachLine }
            val count = countStr.toLongOrNull() ?: run { skipped++; return@forEachLine }
            bigrams.add(Triple(w1, w2, count))
        }
        println("Bigrams: ${bigrams.size} alphabetic, $skipped skipped")

        bigrams.sortByDescending { it.third }
        val top = bigrams.take(maxBigrams)

        // Build vocabulary: word → id
        val vocab = LinkedHashMap<String, Int>()
        for ((w1, w2, _) in top) {
            if (w1 !in vocab) vocab[w1] = vocab.size
            if (w2 !in vocab) vocab[w2] = vocab.size
        }

        // Index as [w1_id, w2_id, count] sorted by (w1_id, -count)
        val indexed = top.map { Triple(vocab[it.first]!!, vocab[it.second]!!, it.third) }
            .sortedWith(compareBy<Triple<Int, Int, Long>> { it.first }.thenByDescending { it.third })

        // Write binary
        outputFile.parentFile?.mkdirs()
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(outputFile))).use { out ->
            out.writeShort(vocab.size)
            out.writeInt(indexed.size)
            for (word in vocab.keys) {
                val bytes = word.toByteArray(Charsets.UTF_8)
                out.writeByte(bytes.size)
                out.write(bytes)
            }
            for ((w1Id, w2Id, count) in indexed) {
                out.writeShort(w1Id)
                out.writeShort(w2Id)
                out.writeInt(count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        }
        println("Written: ${outputFile.name} (${outputFile.length() / 1024} KB, ${vocab.size} words, ${indexed.size} bigrams)")
    }
}
