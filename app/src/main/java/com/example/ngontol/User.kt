package com.example.ngontol.model

import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class User(
    val id: String,
    val username: String,
    val phone: String,
    val password: String,      // ✅ wajib ada
    @SerialName("token_balance")
    val tokenBalance: Int
)
