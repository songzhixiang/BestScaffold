package com.example.bestscaffold.data.remote.interceptor

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

class LoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        Log.d(TAG, "--> ${request.method} ${request.url}")
        val response = chain.proceed(request)
        Log.d(TAG, "<-- ${response.code} ${request.url}")
        return response
    }

    companion object {
        private const val TAG = "Http"
    }
}
