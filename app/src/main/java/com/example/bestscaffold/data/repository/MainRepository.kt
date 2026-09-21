package com.example.bestscaffold.data.repository

import com.example.bestscaffold.data.model.ApiResponse
import com.example.bestscaffold.data.remote.RetrofitClient

class MainRepository {

    private val api = RetrofitClient.instance

    suspend fun getExample(): Result<ApiResponse<String>> {
        return try {
            val response = api.getExample()
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
