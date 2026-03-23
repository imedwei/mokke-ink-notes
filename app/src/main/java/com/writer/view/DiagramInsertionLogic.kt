package com.writer.view

/**
 * Pure logic for computing where to insert a diagram block into the ordered list of
 * text paragraphs in the recognized-text preview panel.
 *
 * Extracted from [RecognizedTextView] so it can be unit-tested without Android dependencies.
 */
internal object DiagramInsertionLogic {

    /**
     * Returns the paragraph index *before* which the diagram block should be rendered.
     *
     * @param paragraphLineIndices for each paragraph, the canvas line indices of its segments
     * @param diagramLineIndex     canvas line index of the topmost diagram node
     *                             ([Int.MAX_VALUE] = no diagram / diagram below all text)
     * @return 0 if diagram is above all text; [Int.MAX_VALUE] if diagram is below all text;
     *         otherwise the index of the first paragraph whose content starts at or below
     *         the diagram.
     */
    fun computeInsertionParagraph(
        paragraphLineIndices: List<List<Int>>,
        diagramLineIndex: Int
    ): Int {
        if (diagramLineIndex == Int.MAX_VALUE) return Int.MAX_VALUE
        val idx = paragraphLineIndices.indexOfFirst { lineIndices ->
            lineIndices.any { li -> li >= diagramLineIndex }
        }
        return if (idx == -1) Int.MAX_VALUE else idx
    }
}
