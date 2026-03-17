package com.writer.recognition

import androidx.test.platform.app.InstrumentationRegistry
import com.writer.model.InkLine
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import org.json.JSONObject

object FixtureLoader {

    data class Fixture(
        val expectedText: String,
        val language: String,
        val inkLine: InkLine
    )

    fun load(name: String): Fixture {
        val context = InstrumentationRegistry.getInstrumentation().context
        val jsonText = context.assets.open("fixtures/$name.json")
            .bufferedReader().use { it.readText() }
        val json = JSONObject(jsonText)

        val expectedText = json.getString("expectedText")
        val language = json.optString("language", "en-US")

        val strokes = mutableListOf<InkStroke>()
        val strokesArr = json.getJSONArray("strokes")
        for (i in 0 until strokesArr.length()) {
            val strokeObj = strokesArr.getJSONObject(i)
            val strokeId = strokeObj.getString("strokeId")

            val pointsArr = strokeObj.getJSONArray("points")
            val points = mutableListOf<StrokePoint>()
            for (j in 0 until pointsArr.length()) {
                val ptObj = pointsArr.getJSONObject(j)
                points.add(
                    StrokePoint(
                        x = ptObj.getDouble("x").toFloat(),
                        y = ptObj.getDouble("y").toFloat(),
                        pressure = ptObj.getDouble("pressure").toFloat(),
                        timestamp = ptObj.getLong("timestamp")
                    )
                )
            }

            strokes.add(InkStroke(strokeId = strokeId, points = points))
        }

        return Fixture(
            expectedText = expectedText,
            language = language,
            inkLine = InkLine.build(strokes)
        )
    }
}
