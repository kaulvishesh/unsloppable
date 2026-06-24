package com.example.slopradar

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private val _sensitivityThreshold = MutableStateFlow(
        prefs.getFloat(Constants.PREF_SENSITIVITY, Constants.DEFAULT_SENSITIVITY)
    )
    val sensitivityThreshold: StateFlow<Float> = _sensitivityThreshold.asStateFlow()

    fun updateSensitivity(value: Float) {
        _sensitivityThreshold.value = value
        // Disk I/O performed asynchronously
        prefs.edit().putFloat(Constants.PREF_SENSITIVITY, value).apply() 
    }
}