package com.example.ui.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SdCardAlert
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.example.data.mobile.outbox.OutboxHealthResult
import com.example.data.mobile.outbox.OutboxHealthStatus
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.ui.theme.PrimaryBlueLight

/**
 * Screen presented when session is revoked or locked (HTTP 401 Auth Denial, Keystore invalidated).
 */
@Composable
fun StaffLockedScreen(
    reason: String?,
    lang: AppLanguage,
    onReAuthenticate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("staff_locked_screen"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = lang.tr("جلسة العمل مقفلة", "Session Locked"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = lang.tr(
                        "انتهت صلاحية الجلسة أو تم قفلها لأسباب أمنية. يرجى تسجيل الدخول مجدداً للمتابعة.",
                        "The staff session is locked or expired. Please sign in again to continue."
                    ),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!reason.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        val localizedReason = when {
                            reason.contains("401") || reason.contains("AuthDenied") || reason.contains("revoked") ->
                                lang.tr("تم إبطال صلاحية الدخول للجلسة", "Session authentication revoked or expired")
                            reason.contains("403") || reason.contains("Forbidden") || reason.contains("MembershipDenied") ->
                                lang.tr("تم رفض الوصول لهذه المنشأة", "Access denied for this company")
                            reason.contains("StorageClearFailed") || reason.contains("clear failed") ->
                                lang.tr("تعذر مسح بيانات الجلسة محلياً", "Local session data could not be cleared")
                            reason.contains("KeyUnavailable") || reason.contains("KeyLocked") ->
                                lang.tr("مفاتيح التشفير الآمنة غير متاحة", "Secure keystore keys unavailable")
                            reason.contains("ScopeMismatch") ->
                                lang.tr("عدم تطابق في بيانات الجلسة والمنشأة", "Session and company scope mismatch")
                            reason.contains("CorruptSession") ->
                                lang.tr("بيانات الجلسة المحفوظة غير صالحة", "Corrupt saved session data")
                            else -> lang.tr("تعذر التحقق من الجلسة. يرجى المحاولة مجدداً", "Could not verify the session. Please try again")
                        }
                        Text(
                            text = localizedReason,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(10.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onReAuthenticate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("staff_locked_reauth_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlueLight)
                ) {
                    Text(
                        text = lang.tr("تسجيل الدخول مجدداً", "Re-authenticate"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Screen presented when persistent storage encounters an unrecoverable disk/Keystore error.
 */
@Composable
fun StaffStorageFailedScreen(
    message: String?,
    lang: AppLanguage,
    onRetry: () -> Unit,
    onReAuthenticate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("staff_storage_failed_screen"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.SdCardAlert,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = lang.tr("فشل في وحدة التخزين الآمنة", "Storage Initialization Failed"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = lang.tr(
                        "تعذر قراءة أو حفظ مفاتيح الجلسة في مساحة التخزين الخاصة بالتطبيق.",
                        "Failed to read or store session keys in application secure storage."
                    ),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("staff_storage_retry_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlueLight)
                ) {
                    Text(
                        text = lang.tr("إعادة المحاولة", "Retry"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onReAuthenticate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("staff_storage_reauth_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = lang.tr("تسجيل الدخول من جديد", "New Sign In"),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * Screen presented on transient transport/network disconnection.
 * Invariant: Never falls back to simulated demo catalog or mock responses.
 */
@Composable
fun StaffOfflineScreen(
    category: String?,
    lang: AppLanguage,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("staff_offline_screen"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = lang.tr("تعذر الاتصال بالخادم", "Server Connection Unavailable"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = lang.tr(
                        "تعذر إكمال طلب الاستعلام بسبب انقطاع الشبكة أو تعذر الوصول إلى الخادم. يرجى التحقق من اتصال الإنترنت وإعادة المحاولة.",
                        "Read query could not complete due to network interruption or unreachable server. Please check your internet connection and try again."
                    ),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("staff_offline_retry_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlueLight)
                ) {
                    Text(
                        text = lang.tr("إعادة المحاولة", "Retry"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Screen presented when STAFF_READ_WORKSPACE opt-in is active but STAFF_WORKSPACE_ORIGIN is empty.
 */
@Composable
fun StaffUnconfiguredScreen(
    lang: AppLanguage,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("staff_unconfigured_screen"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = lang.tr("مساحة استعلام الموظفين غير مهيأة", "Staff Workspace Unconfigured"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = lang.tr(
                        "لم يتم تحديد عنوان الخادم لمساحة استعلام الموظفين. يرجى مراجعة مسؤول النظام لتهيئة إعدادات الخادم.",
                        "Staff workspace server origin is not configured. Please contact your system administrator to configure the server origin."
                    ),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Screen presented when local outbox storage requires recovery or upgrade decision gate.
 *
 * Security & Trust Invariants:
 * 1. Plaintext legacy data has NO cryptographic installation provenance; conversion is NEVER
 *    automatically triggered or exposed via a blind "Convert" button.
 * 2. Unsynced records are safely preserved without deletion, rotation, or auto-unlock.
 * 3. Scope privacy: Never leaks foreign tenant metadata, event IDs, or foreign counts to the UI.
 * 4. Truthful presentation: Never claims success or shows offline capture UI while outbox is blocked.
 * 5. Minimum 48dp touch targets and accessible focus.
 */
@Composable
fun StaffOutboxRecoveryScreen(
    healthResult: OutboxHealthResult?,
    lang: AppLanguage,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    isRetrying: Boolean = false
) {
    val status = healthResult?.status ?: OutboxHealthStatus.CORRUPT
    val icon = when (status) {
        OutboxHealthStatus.LEGACY_PLAINTEXT -> Icons.Default.Security
        OutboxHealthStatus.ENCRYPTED_LOCKED -> Icons.Default.Lock
        OutboxHealthStatus.KEY_UNAVAILABLE -> Icons.Default.SdCardAlert
        OutboxHealthStatus.CORRUPT -> Icons.Default.Warning
        OutboxHealthStatus.MIXED_QUEUE -> Icons.Default.Warning
        else -> Icons.Default.Security
    }

    val title = when (status) {
        OutboxHealthStatus.LEGACY_PLAINTEXT ->
            lang.tr("مراجعة استرداد البيانات السابقة مطلوبة", "Legacy Data Recovery Review Required")
        OutboxHealthStatus.ENCRYPTED_LOCKED ->
            lang.tr("مفتاح التشفير مقفل", "Encryption Key Locked")
        OutboxHealthStatus.KEY_UNAVAILABLE ->
            lang.tr("مفتاح التشفير غير متاح", "Encryption Key Unavailable")
        OutboxHealthStatus.CORRUPT ->
            lang.tr("خطأ في التحقق من التشفير", "Cryptographic Authentication Failed")
        OutboxHealthStatus.MIXED_QUEUE ->
            lang.tr("تعارض في سجلات التخزين", "Conflicting Storage Formats")
        else ->
            lang.tr("فحص وحدة التخزين الآمنة", "Secure Storage Inspection")
    }

    val description = when (status) {
        OutboxHealthStatus.LEGACY_PLAINTEXT ->
            lang.tr(
                "تم اكتشاف سجلات مبيعات سابقة غير مشفرة على هذا الجهاز. لحماية بياناتك المالية، تم حفظ كافة السجلات بأمان تام دون أي تعديل أو حذف. تتطلب الترقية مراجعة واعتماد استرداد موثقة من مالك المنشأة.",
                "Pre-existing unencrypted sales records detected on this device. To protect your economic records, all data is preserved safely without alteration or deletion. Upgrading requires explicit owner review and recovery authority."
            )
        OutboxHealthStatus.ENCRYPTED_LOCKED ->
            lang.tr(
                "مفتاح التشفير الآمن مقفل أو تم إبطاله على هذا الجهاز. سجلات المبيعات غير المرحّلة محفوظة بأمان تام على هذا الجهاز.",
                "The secure encryption key is locked or invalidated on this device. Unsynced sales records are preserved safely on this device."
            )
        OutboxHealthStatus.KEY_UNAVAILABLE ->
            lang.tr(
                "تعذر الوصول إلى مفتاح التشفير الآمن في وحدة التخزين. السجلات غير المرحّلة محفوظة بأمان على هذا الجهاز.",
                "The secure encryption key is unavailable in hardware storage. Unsynced records are safely preserved on this device."
            )
        OutboxHealthStatus.CORRUPT ->
            lang.tr(
                "تعذر التحقق من البنية المشفرة لسجلات التخزين. السجلات غير المرحّلة محفوظة بأمان على هذا الجهاز دون حذف.",
                "Storage records failed cryptographic authentication. Unsynced records are preserved safely on this device without deletion."
            )
        OutboxHealthStatus.MIXED_QUEUE ->
            lang.tr(
                "تحتوي وحدة التخزين على سجلات بتنسيقات متباينة. تم إيقاف العمليات لحماية البيانات، وكافة السجلات محفوظة بأمان.",
                "Outbox storage contains conflicting record formats. Operations are paused to protect data; all records are preserved safely."
            )
        else ->
            if (lang.isRtl) healthResult?.messageAr ?: "" else healthResult?.messageEn ?: ""
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("outbox_recovery_gate_screen"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = if (status == OutboxHealthStatus.LEGACY_PLAINTEXT) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = description,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Preserved data banner
                Spacer(modifier = Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = lang.tr(
                            "كافة السجلات غير المرحّلة محفوظة بأمان على هذا الجهاز",
                            "All unsynced offline records are preserved safely on this device"
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(10.dp),
                        textAlign = TextAlign.Center
                    )
                }

                // Explicit trust / authority notice for legacy plaintext
                if (status == OutboxHealthStatus.LEGACY_PLAINTEXT) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = lang.tr(
                                "تسجيل الدخول للموظفين لا يمنح صلاحية استرداد البيانات. يظل التحويل مغلقاً لحين اعتماد المالك.",
                                "Local staff login does not grant recovery authority. Data upgrade remains closed until authorized."
                            ),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(10.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Safe retry button (never generates key or alters database)
                if (healthResult?.canRetry != false) {
                    Button(
                        onClick = onRetry,
                        enabled = !isRetrying,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("outbox_health_retry_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlueLight)
                    ) {
                        if (isRetrying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (status == OutboxHealthStatus.LEGACY_PLAINTEXT) {
                                    lang.tr("فحص الحالة مجدداً", "Check Status Again")
                                } else {
                                    lang.tr("إعادة المحاولة", "Retry")
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
