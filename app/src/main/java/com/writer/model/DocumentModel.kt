package com.writer.model

class DocumentModel(
    var language: String = java.util.Locale.getDefault().toLanguageTag()
) {
    val main: ColumnModel = ColumnModel()
    val cue: ColumnModel = ColumnModel()
}
