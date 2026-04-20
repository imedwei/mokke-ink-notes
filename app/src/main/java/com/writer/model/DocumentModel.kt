package com.writer.model

class DocumentModel(
    var language: String = java.util.Locale.getDefault().toLanguageTag(),
) {
    val main: ColumnModel = ColumnModel()
    val cue: ColumnModel = ColumnModel()
    val transcript: ColumnModel = ColumnModel()

    private val _audioRecordings: MutableList<AudioRecording> = mutableListOf()
    val audioRecordings: List<AudioRecording> get() = _audioRecordings

    fun hasAnyRecording(): Boolean = _audioRecordings.isNotEmpty()

    fun addAudioRecording(recording: AudioRecording) {
        _audioRecordings.add(recording)
    }

    fun restoreAudioRecordings(recordings: List<AudioRecording>) {
        _audioRecordings.clear()
        _audioRecordings.addAll(recordings)
    }
}
