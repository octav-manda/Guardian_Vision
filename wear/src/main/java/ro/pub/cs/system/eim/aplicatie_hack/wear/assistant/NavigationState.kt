package ro.pub.cs.system.eim.aplicatie_hack.wear.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object NavigationState {

    private val _steps = MutableStateFlow<List<NavStep>>(emptyList())
    val steps: StateFlow<List<NavStep>> = _steps

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    var totalDuration: String = ""
        private set

    val currentStep: NavStep?
        get() = _steps.value.getOrNull(_currentIndex.value)

    val hasActiveNavigation: Boolean
        get() = _steps.value.isNotEmpty() && _currentIndex.value < _steps.value.size

    fun setRoute(result: DirectionsResult) {
        _steps.value        = result.steps
        _currentIndex.value = 0
        totalDuration       = result.totalDuration
    }

    fun advance(): NavStep? {
        val next = _currentIndex.value + 1
        return if (next < _steps.value.size) {
            _currentIndex.value = next
            _steps.value[next]
        } else {
            clear()
            null
        }
    }

    fun clear() {
        _steps.value        = emptyList()
        _currentIndex.value = 0
        totalDuration       = ""
    }
}