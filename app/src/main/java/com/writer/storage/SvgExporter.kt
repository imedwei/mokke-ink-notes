package com.writer.storage

import android.util.Base64
import com.writer.model.InkStroke

object SvgExporter {

    fun strokesToSvg(
        strokes: List<InkStroke>,
        width: Float,
        height: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ): String {
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
        sb.append("width=\"${width.toInt()}\" height=\"${height.toInt()}\" ")
        sb.append("viewBox=\"0 0 ${width.toInt()} ${height.toInt()}\">")

        for (stroke in strokes) {
            val pts = stroke.points
            if (pts.size < 2) continue
            sb.append("<path d=\"")
            sb.append("M ${fmt(pts[0].x - offsetX)} ${fmt(pts[0].y - offsetY)}")
            for (i in 1 until pts.size) {
                val prev = pts[i - 1]
                val curr = pts[i]
                val midX = (prev.x - offsetX + curr.x - offsetX) / 2f
                val midY = (prev.y - offsetY + curr.y - offsetY) / 2f
                sb.append(" Q ${fmt(prev.x - offsetX)} ${fmt(prev.y - offsetY)} ${fmt(midX)} ${fmt(midY)}")
            }
            val last = pts.last()
            sb.append(" L ${fmt(last.x - offsetX)} ${fmt(last.y - offsetY)}")
            sb.append("\" fill=\"none\" stroke=\"black\" stroke-width=\"${fmt(stroke.strokeWidth)}\" ")
            sb.append("stroke-linecap=\"round\" stroke-linejoin=\"round\"/>")
        }

        sb.append("</svg>")
        return sb.toString()
    }

    fun toBase64DataUri(svg: String): String {
        val encoded = Base64.encodeToString(svg.toByteArray(), Base64.NO_WRAP)
        return "data:image/svg+xml;base64,$encoded"
    }

    private fun fmt(v: Float): String = "%.1f".format(v)
}
