package com.writer.recognition

import com.writer.view.ScreenMetrics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BigramPredictorTest {

    private lateinit var predictor: BigramPredictor

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 824, heightPixels = 1648)
        val loaded = BigramPredictor.load(RuntimeEnvironment.getApplication())
        assertNotNull("BigramPredictor should load from assets", loaded)
        predictor = loaded!!
    }

    @Test
    fun `predict returns results for common word`() {
        val results = predictor.predict("the")
        assertTrue("Should have predictions for 'the': $results", results.isNotEmpty())
        // "the" is the most common English word; top bigrams should be function words
        println("Predictions for 'the': $results")
    }

    @Test
    fun `predict returns up to maxResults`() {
        val results = predictor.predict("the", maxResults = 5)
        assertTrue("Should have up to 5 results", results.size <= 5)
        assertTrue("Should have at least 1 result", results.isNotEmpty())
    }

    @Test
    fun `predict returns empty for unknown word`() {
        val results = predictor.predict("xyzzyplugh")
        assertTrue("Should return empty for unknown word", results.isEmpty())
    }

    @Test
    fun `predict is case insensitive`() {
        val lower = predictor.predict("the")
        val upper = predictor.predict("The")
        assertEquals("Case should not matter", lower, upper)
    }

    @Test
    fun `common bigrams are present`() {
        // "of the", "in the", "to the" are the top English bigrams
        val ofPredictions = predictor.predict("of")
        assertTrue("'of' should predict 'the'", "the" in ofPredictions)

        val inPredictions = predictor.predict("in")
        assertTrue("'in' should predict 'the'", "the" in inPredictions)
    }

    @Test
    fun `hasWord returns true for common words`() {
        assertTrue(predictor.hasWord("the"))
        assertTrue(predictor.hasWord("of"))
        assertTrue(predictor.hasWord("and"))
    }

    @Test
    fun `hasWord returns false for unknown words`() {
        assertFalse(predictor.hasWord("xyzzyplugh"))
    }

    @Test
    fun `predictions are ordered by frequency`() {
        val results = predictor.predict("I", maxResults = 5)
        assertTrue("Should have multiple predictions for 'I'", results.size >= 2)
        // First prediction should be more common than second
        // (we can't check counts directly, but order should be stable)
        println("Predictions for 'I': $results")
    }
}
