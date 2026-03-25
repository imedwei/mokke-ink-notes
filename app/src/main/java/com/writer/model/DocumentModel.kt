package com.writer.model

class DocumentModel(
    var language: String = "en-US"
) {
    val main: ColumnModel = ColumnModel()
    val cue: ColumnModel = ColumnModel()
}
