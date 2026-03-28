package com.writer.tools.viewer

import com.writer.model.proto.DocumentProto
import java.awt.Desktop
import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: inkup-viewer <file.inkup> [output.html]")
        System.exit(1)
    }

    val inputFile = File(args[0])
    if (!inputFile.exists()) {
        System.err.println("File not found: ${inputFile.absolutePath}")
        System.exit(1)
    }

    val outputFile = if (args.size > 1) {
        File(args[1])
    } else {
        File(inputFile.absolutePath.replace(".inkup", "") + ".html")
    }

    val bytes = inputFile.readBytes()
    val doc = DocumentProto.ADAPTER.decode(bytes)

    val filename = inputFile.nameWithoutExtension
    val html = HtmlRenderer.render(doc, filename)
    outputFile.writeText(html)

    println("Written to ${outputFile.absolutePath}")
    println("Main: ${doc.main?.strokes?.size ?: 0} strokes, Cue: ${doc.cue?.strokes?.size ?: 0} strokes")

    if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().browse(outputFile.toURI())
    }
}
