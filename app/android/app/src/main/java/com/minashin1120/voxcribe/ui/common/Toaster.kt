package com.minashin1120.voxcribe.ui.common

import androidx.compose.runtime.mutableStateListOf

data class ToastMsg(val id: Long, val text: String, val isError: Boolean)

/** Web版 showToast(m, isError) 相当 */
class Toaster {
    val items = mutableStateListOf<ToastMsg>()
    private var seq = 0L

    fun show(text: String, isError: Boolean = false) {
        val msg = ToastMsg(++seq, text, isError)
        items.add(msg)
        while (items.size > 3) items.removeAt(0)
    }

    fun dismiss(id: Long) {
        items.removeAll { it.id == id }
    }
}
