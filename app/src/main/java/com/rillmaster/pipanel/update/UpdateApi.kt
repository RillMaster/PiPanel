package com.rillmaster.pipanel.update

import retrofit2.http.GET
import retrofit2.http.Url

data class VersionInfo(
    val versionCode: Long,
    val versionName: String,
    val url: String
)

interface UpdateApi {
    @GET
    suspend fun getVersion(@Url url: String): VersionInfo

    @GET
    suspend fun getChangelog(@Url url: String): okhttp3.ResponseBody
}
