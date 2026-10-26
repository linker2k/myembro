// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

/** In-memory request only, shared across hand-off and returned image. Consent is never retained. */
class AiPictureDraft : ViewModel() {
    val request = mutableStateOf("")
    val colors = mutableStateOf(AiPicturePrompt.DEFAULT_COLORS)
    val photo = mutableStateOf<Bitmap?>(null)
    val format = mutableStateOf(AiPicturePrompt.Format.PNG)
    private var awaitingReturn = false
    fun expectImage() { awaitingReturn = true }
    fun cancelExpectedImage() { awaitingReturn = false }
    fun consumeExpectedColors(): Int? = if (awaitingReturn) colors.value.also { awaitingReturn = false } else null
    private var owned: Bitmap? = null
    fun picked(bitmap: Bitmap) { owned?.recycle(); owned = bitmap; photo.value = bitmap }
    fun retryPng() { format.value = AiPicturePrompt.Format.PNG }
    override fun onCleared() { owned?.recycle(); owned = null; photo.value = null }
}
