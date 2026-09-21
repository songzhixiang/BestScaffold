package com.example.bestscaffold.ui.main

import com.example.bestscaffold.data.repository.MainRepository
import com.example.bestscaffold.ui.base.BaseViewModel

class MainViewModel(
    private val repository: MainRepository = MainRepository()
) : BaseViewModel<MainEvent, MainState, MainEffect>(MainState()) {

    override fun handleEvent(event: MainEvent) {
        when (event) {
            is MainEvent.OnTextChanged -> {
                updateState { copy(text = event.text) }
            }
            is MainEvent.OnButtonClick -> {
                sendEffect(MainEffect.ShowToast("Button clicked"))
            }
        }
    }
}
