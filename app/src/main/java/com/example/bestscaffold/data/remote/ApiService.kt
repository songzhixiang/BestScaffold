package com.example.bestscaffold.data.remote

import com.example.bestscaffold.data.model.ApiResponse
import retrofit2.http.GET

interface ApiService {

    @GET("api/example")
    suspend fun getExample(): ApiResponse<String>
}
