package com.nexora.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexora.player.api.PlayerCommand
import com.nexora.player.api.PlayerController
import com.nexora.player.media3.createMedia3PlayerEngine
import com.nexora.player.runtime.RuntimePlayerController
import kotlinx.coroutines.runBlocking

internal class MobilePlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    val controller: PlayerController = RuntimePlayerController(
        engine = createMedia3PlayerEngine(application.applicationContext),
        scope = viewModelScope,
    )

    override fun onCleared() {
        runBlocking {
            controller.dispatch(PlayerCommand.Close)
        }
        super.onCleared()
    }

    class Factory(
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MobilePlayerViewModel::class.java)) {
                "Unsupported ViewModel type: ${modelClass.name}"
            }
            return MobilePlayerViewModel(application) as T
        }
    }
}
