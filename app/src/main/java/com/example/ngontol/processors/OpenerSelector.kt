package com.example.ngontol.processors

import com.example.ngontol.OpenerData
import java.util.Calendar

object OpenerSelector {
    fun getOpener(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String {
        return when (hour) {
            in 5..9 -> OpenerData.morningOpeners.random()
            in 10..15 -> OpenerData.noonOpeners.random()
            in 16..19 -> OpenerData.genZOpeners.random()
            in 20..23 -> OpenerData.nightOpeners.random()
            else -> OpenerData.lateNightOpeners.random()
        }
    }

    fun shouldUseOpener(message: String): Boolean {
        return OpenerData.cocokPatterns.any { message.contains(it) } ||
                OpenerData.partnerPatterns.any { message.contains(it) }
    }
}