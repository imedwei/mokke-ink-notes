package com.writer.view

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagramInsertionLogicTest {

    @Test
    fun diagramAboveAllText_insertsFirst() {
        val paragraphs = listOf(listOf(2, 3), listOf(4, 5))
        val result = DiagramInsertionLogic.computeInsertionParagraph(paragraphs, 0)
        assertEquals(0, result)
    }

    @Test
    fun diagramBelowAllText_insertsLast() {
        val paragraphs = listOf(listOf(0, 1), listOf(2, 3))
        val result = DiagramInsertionLogic.computeInsertionParagraph(paragraphs, 10)
        assertEquals(Int.MAX_VALUE, result)
    }

    @Test
    fun diagramBetweenParagraphs_insertsAtCorrectIndex() {
        val paragraphs = listOf(listOf(0, 1), listOf(4, 5), listOf(8, 9))
        val result = DiagramInsertionLogic.computeInsertionParagraph(paragraphs, 3)
        assertEquals(1, result)
    }

    @Test
    fun diagramAtSameLineAsParagraph_insertsBefore() {
        val paragraphs = listOf(listOf(0), listOf(3), listOf(6))
        val result = DiagramInsertionLogic.computeInsertionParagraph(paragraphs, 3)
        assertEquals(1, result)
    }

    @Test
    fun noParagraphs_diagramPresent_returnsMaxValue() {
        val paragraphs = emptyList<List<Int>>()
        val result = DiagramInsertionLogic.computeInsertionParagraph(paragraphs, 5)
        assertEquals(Int.MAX_VALUE, result)
    }

    @Test
    fun noDiagram_returnsMaxValue() {
        val paragraphs = listOf(listOf(0, 1), listOf(2, 3))
        val result = DiagramInsertionLogic.computeInsertionParagraph(paragraphs, Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, result)
    }

    @Test
    fun singleParagraph_diagramAbove_returnsZero() {
        val paragraphs = listOf(listOf(5, 6))
        val result = DiagramInsertionLogic.computeInsertionParagraph(paragraphs, 2)
        assertEquals(0, result)
    }

    @Test
    fun singleParagraph_diagramBelow_returnsMaxValue() {
        val paragraphs = listOf(listOf(0, 1))
        val result = DiagramInsertionLogic.computeInsertionParagraph(paragraphs, 5)
        assertEquals(Int.MAX_VALUE, result)
    }
}
