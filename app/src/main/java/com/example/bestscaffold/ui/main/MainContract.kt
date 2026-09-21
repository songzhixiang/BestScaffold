package com.example.bestscaffold.ui.main

data class MainState(
    val text: String = "",
    val isLoading: Boolean = false
)

sealed class MainEvent {
    data class OnTextChanged(val text: String) : MainEvent()
    data object OnButtonClick : MainEvent()
}

sealed class MainEffect {
    data class ShowToast(val message: String) : MainEffect()
}
