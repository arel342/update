package com.nodelix.coreos.network

import com.nodelix.coreos.data.model.CalibrateRequestDto
import com.nodelix.coreos.data.model.CompressFileRequestDto
import com.nodelix.coreos.data.model.CreateFolderRequestDto
import com.nodelix.coreos.data.model.DeviceTestRequestDto
import com.nodelix.coreos.data.model.DeviceTestResponseDto
import com.nodelix.coreos.data.model.DoorCardAdminRequestDto
import com.nodelix.coreos.data.model.DoorCardRenameRequestDto
import com.nodelix.coreos.data.model.DoorConfigRequestDto
import com.nodelix.coreos.data.model.DoorEnrollRequestDto
import com.nodelix.coreos.data.model.DoorLockToggleRequestDto
import com.nodelix.coreos.data.model.DoorStatusDto
import com.nodelix.coreos.data.model.Esp32StatusDto
import com.nodelix.coreos.data.model.FileListResponseDto
import com.nodelix.coreos.data.model.FirmwareFlashResponseDto
import com.nodelix.coreos.data.model.FirmwareStatusResponseDto
import com.nodelix.coreos.data.model.GenericStatusDto
import com.nodelix.coreos.data.model.LangDto
import com.nodelix.coreos.data.model.LoginRequestDto
import com.nodelix.coreos.data.model.MobileChallengeRequestDto
import com.nodelix.coreos.data.model.MobileChallengeResponseDto
import com.nodelix.coreos.data.model.MobileRegisterRequestDto
import com.nodelix.coreos.data.model.MobileRegisterResponseDto
import com.nodelix.coreos.data.model.MobileSessionDto
import com.nodelix.coreos.data.model.MobileVerifyRequestDto
import com.nodelix.coreos.data.model.MobileVerifyResponseDto
import com.nodelix.coreos.data.model.OtpVerifyRequestDto
import com.nodelix.coreos.data.model.PasteRequestDto
import com.nodelix.coreos.data.model.RegisteredDevicesResponseDto
import com.nodelix.coreos.data.model.RelayCommandRequestDto
import com.nodelix.coreos.data.model.RelayCommandResponseDto
import com.nodelix.coreos.data.model.RenameFileRequestDto
import com.nodelix.coreos.data.model.ResetPasswordRequestDto
import com.nodelix.coreos.data.model.SessionCheckDto
import com.nodelix.coreos.data.model.SettingsSnapshotDto
import com.nodelix.coreos.data.model.StorageStatusDto
import com.nodelix.coreos.data.model.SystemResponseDto
import com.nodelix.coreos.data.model.UpdateAccountRequestDto
import com.nodelix.coreos.data.model.UpdateServerSettingsRequestDto
import com.nodelix.coreos.data.model.UpdaterStatusDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface ApiService {

    // ==================== CONNECTION PROBE & LEGACY AUTH (server.js) ====================

    @GET("api/settings/lang")
    suspend fun getServerLanguage(): Response<LangDto>

    @GET("api/session")
    suspend fun getLegacySession(): Response<SessionCheckDto>

    @POST("api/login")
    suspend fun loginLegacy(@Body body: LoginRequestDto): Response<GenericStatusDto>

    @POST("api/logout")
    suspend fun logoutLegacy(): Response<GenericStatusDto>

    @POST("api/request-otp")
    suspend fun requestOtp(): Response<GenericStatusDto>

    @POST("api/verify-otp")
    suspend fun verifyOtp(@Body body: OtpVerifyRequestDto): Response<GenericStatusDto>

    @POST("api/reset-password")
    suspend fun resetPassword(@Body body: ResetPasswordRequestDto): Response<GenericStatusDto>

    // ==================== MOBILE ECDSA AUTH CONTRACT (/api/mobile/...) ====================

    @POST("api/mobile/auth/register")
    suspend fun registerMobileDevice(@Body body: MobileRegisterRequestDto): Response<MobileRegisterResponseDto>

    @POST("api/mobile/auth/challenge")
    suspend fun requestMobileChallenge(@Body body: MobileChallengeRequestDto): Response<MobileChallengeResponseDto>

    @POST("api/mobile/auth/verify")
    suspend fun verifyMobileChallenge(@Body body: MobileVerifyRequestDto): Response<MobileVerifyResponseDto>

    @GET("api/mobile/auth/session")
    suspend fun getMobileSession(): Response<MobileSessionDto>

    @POST("api/mobile/auth/logout")
    suspend fun logoutMobile(): Response<GenericStatusDto>

    /**
     * Rotates the current Bearer session (`Authorization: Bearer <sessionToken>` required).
     * The old token is revoked and a fresh `sessionToken` is issued (mobile TTL 720h).
     */
    @POST("api/mobile/auth/refresh")
    suspend fun refreshMobileSession(): Response<MobileVerifyResponseDto>

    @GET("api/mobile/devices/registered")
    suspend fun getRegisteredMobileDevices(): Response<RegisteredDevicesResponseDto>

    @DELETE("api/mobile/devices/registered/{id}")
    suspend fun revokeRegisteredMobileDevice(@Path("id") deviceId: String): Response<GenericStatusDto>

    // ==================== SYSTEM & SETTINGS (system.js, settings.js) ====================

    @GET("api/system")
    suspend fun getSystemMetrics(): Response<SystemResponseDto>

    @GET("api/settings")
    suspend fun getServerSettings(): Response<SettingsSnapshotDto>

    /**
     * Partial server settings update (`settings.js apply()`): only the provided
     * `{ group: { field: value } }` entries are stored; unknown fields are ignored.
     */
    @POST("api/settings")
    suspend fun updateServerSettings(@Body body: UpdateServerSettingsRequestDto): Response<GenericStatusDto>

    /**
     * Admin account change. Requires the CURRENT dashboard password in `password`;
     * other web sessions are revoked when the password changes.
     */
    @POST("api/settings/account")
    suspend fun updateAccountSettings(@Body body: UpdateAccountRequestDto): Response<GenericStatusDto>

    /** Sends a Telegram test message via the server-configured bot (`server.js`). */
    @POST("api/settings/telegram/test")
    suspend fun testTelegramNotification(): Response<GenericStatusDto>

    @POST("api/settings/device/test")
    suspend fun testDownstreamDevice(@Body body: DeviceTestRequestDto): Response<DeviceTestResponseDto>

    // ==================== ESP32 & RELAYS (esp32.js) ====================

    @GET("api/esp32/status")
    suspend fun getEsp32Status(): Response<Esp32StatusDto>

    @POST("api/esp32/relay")
    suspend fun controlRelay(@Body body: RelayCommandRequestDto): Response<RelayCommandResponseDto>

    @POST("api/esp32/calibrate")
    suspend fun calibrateEsp32(@Body body: CalibrateRequestDto): Response<GenericStatusDto>

    // ==================== ESP8266 DOOR LOCK (door.js) ====================

    @GET("api/door/status")
    suspend fun getDoorStatus(): Response<DoorStatusDto>

    @POST("api/door/open")
    suspend fun openDoor(): Response<GenericStatusDto>

    @POST("api/door/close")
    suspend fun closeDoor(): Response<GenericStatusDto>

    @POST("api/door/lock")
    suspend fun setDoorRfidLock(@Body body: DoorLockToggleRequestDto): Response<GenericStatusDto>

    @POST("api/door/config")
    suspend fun setDoorOpenDuration(@Body body: DoorConfigRequestDto): Response<GenericStatusDto>

    @POST("api/door/enroll")
    suspend fun setDoorEnroll(@Body body: DoorEnrollRequestDto): Response<GenericStatusDto>

    @POST("api/door/cards/rename")
    suspend fun renameDoorCard(@Body body: DoorCardRenameRequestDto): Response<GenericStatusDto>

    @POST("api/door/cards/admin")
    suspend fun setDoorCardAdmin(@Body body: DoorCardAdminRequestDto): Response<GenericStatusDto>

    @DELETE("api/door/cards/{uid}")
    suspend fun deleteDoorCard(@Path("uid") uid: String): Response<GenericStatusDto>

    // ==================== STORAGE & FILE MANAGER (storage.js, server.js) ====================

    @GET("api/storage/status")
    suspend fun getStorageStatus(): Response<StorageStatusDto>

    @GET("api/files")
    suspend fun listFiles(@Query("dir") relativeDir: String): Response<FileListResponseDto>

    @POST("api/create-folder")
    suspend fun createFolder(@Body body: CreateFolderRequestDto): Response<GenericStatusDto>

    @POST("api/rename")
    suspend fun renameFile(@Body body: RenameFileRequestDto): Response<GenericStatusDto>

    @POST("api/compress")
    suspend fun compressFile(@Body body: CompressFileRequestDto): Response<GenericStatusDto>

    @POST("api/paste")
    suspend fun pasteEntries(@Body body: PasteRequestDto): Response<GenericStatusDto>

    @DELETE("api/files")
    suspend fun deleteFile(@Query("path") relativePath: String): Response<GenericStatusDto>

    @Multipart
    @POST("api/upload")
    suspend fun uploadFile(
        @Query("dir") relativeDir: String,
        @Part file: MultipartBody.Part
    ): Response<GenericStatusDto>

    @Streaming
    @GET("api/download")
    suspend fun downloadFile(
        @Query("path") relativePath: String,
        @Query("zip") zipFlag: String? = null,
        @Header("Range") byteRange: String? = null
    ): Response<ResponseBody>

    // ==================== FIRMWARE & UPDATE CENTER (firmware.js, updater.js) ====================

    @GET("api/firmware")
    suspend fun getFirmwareDevices(): Response<FirmwareStatusResponseDto>

    @Multipart
    @POST("api/firmware/flash")
    suspend fun flashFirmware(
        @Part("device") device: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<FirmwareFlashResponseDto>

    @GET("api/update/status")
    suspend fun getServerUpdateStatus(): Response<UpdaterStatusDto>

    @POST("api/update/check")
    suspend fun checkServerUpdate(): Response<UpdaterStatusDto>

    /** Re-sends the pending server-update notification to the admin Telegram chat. */
    @POST("api/update/notify")
    suspend fun resendUpdateNotification(): Response<UpdaterStatusDto>
}
