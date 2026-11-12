package com.nodelix.coreos.network

import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.data.local.DiagnosticLogStore
import com.nodelix.coreos.data.model.GenericStatusDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownServiceException
import java.net.UnknownHostException

class LanHttpBlockedException(message: String) : IOException(message)

object NetworkResultMapper {

    fun <T> mapResponse(
        response: Response<T>,
        json: Json,
        onUnauthorized: (() -> Unit)? = null
    ): AppResult<T> {
        if (response.isSuccessful) {
            val body = response.body()
            return if (body != null) {
                AppResult.Success(body)
            } else {
                AppResult.Failure(AppError.MalformedPayload("Empty response body from server."))
            }
        }

        val errorRaw = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()
        val parsedError = runCatching {
            if (errorRaw.isNotBlank()) json.decodeFromString<GenericStatusDto>(errorRaw) else null
        }.getOrNull()
        // Prefer structured message fields; fall back to the machine-readable mobile
        // error code (e.g. `device_not_registered`) so failures stay actionable.
        val serverMsg = parsedError?.resolvedMessage?.takeIf { it.isNotBlank() }
            ?: parsedError?.code?.takeIf { it.isNotBlank() }

        return mapHttpCode(
            code = response.code(),
            serverMsg = serverMsg,
            isSetupRequired = parsedError?.setup == true,
            rawHint = errorRaw,
            onUnauthorized = onUnauthorized
        )
    }

    fun mapThrowable(
        throwable: Throwable,
        json: Json,
        logStore: DiagnosticLogStore? = null,
        tag: String = "Network",
        onUnauthorized: (() -> Unit)? = null
    ): AppResult.Failure {
        logStore?.warn(tag, "Request failed: ${throwable.javaClass.simpleName}: ${throwable.message ?: ""}")

        if (throwable is HttpException) {
            val errorRaw = runCatching { throwable.response()?.errorBody()?.string() }.getOrNull().orEmpty()
            val parsedError = runCatching {
                if (errorRaw.isNotBlank()) json.decodeFromString<GenericStatusDto>(errorRaw) else null
            }.getOrNull()
            val serverMsg = parsedError?.resolvedMessage?.takeIf { it.isNotBlank() }
                ?: parsedError?.code?.takeIf { it.isNotBlank() }
            return mapHttpCode(
                code = throwable.code(),
                serverMsg = serverMsg,
                isSetupRequired = parsedError?.setup == true,
                rawHint = errorRaw,
                onUnauthorized = onUnauthorized
            )
        }

        // OkHttp refuses cleartext when the platform policy forbids it; surface that as an
        // explicit LAN/cleartext error instead of a generic "network I/O interrupted".
        if (throwable is UnknownServiceException &&
            throwable.message?.contains("CLEARTEXT", ignoreCase = true) == true
        ) {
            return AppResult.Failure(
                AppError.LanHttpBlocked(
                    "HTTP is blocked by Android network policy for this host."
                )
            )
        }

        val appError = when (throwable) {
            is LanHttpBlockedException -> AppError.LanHttpBlocked(throwable.message ?: "LAN HTTP blocked.")
            is SocketTimeoutException,
            is InterruptedIOException -> AppError.Timeout()
            is ConnectException -> AppError.ConnectionRefused()
            is UnknownHostException,
            is NoRouteToHostException -> AppError.Offline("Server host could not be reached on the current network.")
            is SerializationException -> AppError.MalformedPayload()
            is IOException -> AppError.Offline("Network I/O interrupted while communicating with server.")
            else -> AppError.Unknown()
        }
        return AppResult.Failure(appError)
    }

    private fun mapHttpCode(
        code: Int,
        serverMsg: String?,
        isSetupRequired: Boolean,
        rawHint: String,
        onUnauthorized: (() -> Unit)?
    ): AppResult.Failure {
        val err = when (code) {
            400 -> AppError.BadRequest(serverMsg ?: "Invalid request parameters (HTTP 400).")
            401 -> {
                onUnauthorized?.invoke()
                AppError.AuthRequired(serverMsg ?: "Authentication required or session expired (HTTP 401).")
            }
            403 -> {
                if (rawHint.contains("revok", ignoreCase = true) ||
                    serverMsg?.contains("revok", ignoreCase = true) == true
                ) {
                    onUnauthorized?.invoke()
                    AppError.DeviceRevoked(serverMsg ?: "Device access has been revoked.")
                } else {
                    AppError.Forbidden(serverMsg ?: "Forbidden by server policy (HTTP 403).")
                }
            }
            404 -> AppError.NotFound(serverMsg ?: "Endpoint or resource not found (HTTP 404).")
            405 -> AppError.BadRequest(serverMsg ?: "HTTP method not supported for this endpoint (HTTP 405).")
            409 -> {
                if (isSetupRequired) {
                    AppError.SetupRequired(serverMsg ?: "Complete initial server setup at /setup.html first.")
                } else {
                    AppError.BadRequest(serverMsg ?: "Operation conflict on server (HTTP 409).")
                }
            }
            413 -> AppError.BadRequest(serverMsg ?: "Uploaded file exceeds server maximum size limit (HTTP 413).")
            429 -> AppError.RateLimited(serverMsg ?: "Too many requests. Please wait before retrying (HTTP 429).")
            502, 503, 504 -> AppError.DeviceOffline(serverMsg ?: "Downstream ESP device did not respond (HTTP $code).")
            in 500..599 -> AppError.ServerError(serverMsg ?: "Server internal error (HTTP $code).")
            else -> AppError.Unknown(serverMsg ?: "Unexpected HTTP status $code.")
        }
        return AppResult.Failure(err)
    }
}
