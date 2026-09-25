package com.github.kr328.clash.service

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.data.RuleOverrideDao
import com.github.kr328.clash.service.data.RuleProviderDao
import com.github.kr328.clash.service.model.CustomRule
import com.github.kr328.clash.service.model.CustomRuleProvider
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.RuleProviderBehavior
import com.github.kr328.clash.service.model.RuleProviderFormat
import com.github.kr328.clash.service.model.RuleProviderType
import com.github.kr328.clash.service.model.RuleProviderUpdateInterval
import com.github.kr328.clash.service.model.RuleType
import com.github.kr328.clash.service.override.RuleOverrideApplier
import com.github.kr328.clash.service.override.RuleOverrideException
import com.github.kr328.clash.service.override.RuleOverrideFailureTracker
import com.github.kr328.clash.service.override.RuleProviderOverrideApplier
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.processingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit

object ProfileProcessor {
    private val profileLock = Mutex()
    private val processLock = Mutex()

    suspend fun apply(context: Context, uuid: UUID, callback: IFetchObserver? = null) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val pending =
                        PendingDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                    pending.enforceFieldValid()

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.pendingDir.resolve(pending.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    pending
                }

                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })

                val force = snapshot.type != Profile.Type.File
                val subscriptionInfo = fetchProfile(context, snapshot.source, force, callback)

                applyRuleOverrides(context, snapshot.uuid, snapshot.source)

                profileLock.withLock {
                    if (PendingDao().queryByUUID(snapshot.uuid) == snapshot) {
                        context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively()
                        context.processingDir.copyRecursively(context.importedDir.resolve(snapshot.uuid.toString()))

                        val old = ImportedDao().queryByUUID(snapshot.uuid)
                        val updateInterval = subscriptionInfo?.subUpdateInterval
                            ?.takeIf { old == null && snapshot.interval == 0L }
                            ?: snapshot.interval
                        val new = Imported(
                            snapshot.uuid,
                            snapshot.name,
                            snapshot.type,
                            snapshot.source,
                            updateInterval,
                            subscriptionInfo?.subUpload ?: 0,
                            subscriptionInfo?.subDownload ?: 0,
                            subscriptionInfo?.subTotal ?: 0,
                            subscriptionInfo?.subExpire ?: 0,
                            old?.createdAt ?: System.currentTimeMillis(),
                            ageSecretKey = snapshot.ageSecretKey
                        )
                        if (old != null) {
                            ImportedDao().update(new)
                        } else {
                            ImportedDao().insert(new)
                        }

                        PendingDao().remove(snapshot.uuid)

                        context.pendingDir.resolve(snapshot.uuid.toString()).deleteRecursively()

                        context.sendProfileChanged(snapshot.uuid)
                    }
                }
            }
        }
    }

    suspend fun update(context: Context, uuid: UUID, callback: IFetchObserver?) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val imported =
                        ImportedDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.importedDir.resolve(imported.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    imported
                }

                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })

                val subscriptionInfo = fetchProfile(context, snapshot.source, true, callback)

                applyRuleOverrides(context, snapshot.uuid, snapshot.source)

                profileLock.withLock {
                    val imported = ImportedDao().queryByUUID(snapshot.uuid)
                    if (imported != null) {
                        context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively()
                        context.processingDir.copyRecursively(context.importedDir.resolve(snapshot.uuid.toString()))

                        val upload = subscriptionInfo?.subUpload
                        if (upload != null) {
                            ImportedDao().update(
                                imported.copy(
                                    upload = upload,
                                    download = subscriptionInfo.subDownload ?: 0,
                                    total = subscriptionInfo.subTotal ?: 0,
                                    expire = subscriptionInfo.subExpire ?: 0,
                                )
                            )
                        }

                        context.sendProfileChanged(snapshot.uuid)
                    }
                }
            }
        }
    }

    private suspend fun fetchProfile(
        context: Context,
        source: String,
        force: Boolean,
        callback: IFetchObserver?,
    ): FetchStatus? {
        var subscriptionInfo: FetchStatus? = null
        var cb = callback

        Clash.fetchAndValid(context.processingDir, source, force) {
            if (it.action == FetchStatus.Action.SubscriptionInfo) {
                subscriptionInfo = it
                return@fetchAndValid
            }

            try {
                cb?.updateStatus(it)
            } catch (e: Exception) {
                cb = null

                Log.w("Report fetch status: $e", e)
            }
        }.await()

        return subscriptionInfo
    }

    /**
     * 在 processingDir 的 config.yaml 内应用该 profile 绑定的自定义规则（ADR-001 方案 A Seq）。
     * 合并前先剔除上一轮由本功能注入的行（RuleOverrideApplier 的 marker 机制），避免
     * Type.File 类型 profile 因 force=false 不重新拉取而导致规则重复叠加（Review 阻断项 A1）。
     * 合并写入后用 Clash.fetchAndValid(processingDir, source, force = false) 做一次内核级
     * 重校验——该入口在 config.yaml 已存在时不重新下载、不调用 hub.ApplyConfig，不触碰运行中
     * 内核，可发现策略名指向不存在 proxy-group 这类语义错误（Review 阻断项 A2）。
     * 任一步失败均抛异常且不落地为有效变更（语法失败不写文件，内核校验失败回滚文件内容），
     * 调用方据此不执行后续拷贝，旧配置原样生效。规则集为空且此前从未注入过时直接返回，
     * 不触碰 config.yaml，不影响未使用该功能的用户。
     */
    private suspend fun applyRuleOverrides(context: Context, uuid: UUID, source: String) {
        val overrides = RuleOverrideDao().queryByProfile(uuid)

        val rules = overrides.map { override ->
            val type = RuleType.fromLiteral(override.ruleType)
                ?: throw RuleOverrideException("未知规则类型：${override.ruleType}")
            CustomRule(type, override.content, override.policy, override.position)
        }

        try {
            RuleOverrideApplier.applyToFile(context.processingDir.resolve("config.yaml"), rules) { dir ->
                Clash.fetchAndValid(dir, source, force = false) {}.await()
            }
            RuleOverrideFailureTracker.onApplySucceeded(uuid, overrides)
        } catch (e: Exception) {
            RuleOverrideFailureTracker.onApplyFailed(uuid, overrides)
            throw e
        }

        applyRuleProviders(context, uuid, source)
    }

    /**
     * 在同一次 processingDir 校验流水线内，紧接自定义规则之后应用该 profile 绑定的规则集定义
     * （分派卡真实 delta 一）。复用 [RuleOverrideApplier] 同款 marker 幂等 + 内核重校验回滚语义，
     * 失败即整体不落地，旧配置原样生效。
     */
    private suspend fun applyRuleProviders(context: Context, uuid: UUID, source: String) {
        val providers = RuleProviderDao().queryByProfile(uuid).map { provider ->
            CustomRuleProvider(
                name = provider.name,
                type = RuleProviderType.fromLiteral(provider.type)
                    ?: throw RuleOverrideException("未知规则集类型：${provider.type}"),
                behavior = RuleProviderBehavior.fromLiteral(provider.behavior)
                    ?: throw RuleOverrideException("未知规则集匹配语义：${provider.behavior}"),
                format = RuleProviderFormat.fromLiteral(provider.format)
                    ?: throw RuleOverrideException("未知规则集格式：${provider.format}"),
                url = provider.url,
                updateInterval = RuleProviderUpdateInterval.fromSeconds(provider.updateIntervalSeconds),
            )
        }

        RuleProviderOverrideApplier.applyToFile(context.processingDir.resolve("config.yaml"), providers) { dir ->
            Clash.fetchAndValid(dir, source, force = false) {}.await()
        }
    }

    suspend fun delete(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                ImportedDao().remove(uuid)
                PendingDao().remove(uuid)
                RuleOverrideDao().removeByProfile(uuid)
                RuleOverrideFailureTracker.onProfileRemoved(uuid)
                RuleProviderDao().removeByProfile(uuid)

                val pending = context.pendingDir.resolve(uuid.toString())
                val imported = context.importedDir.resolve(uuid.toString())

                pending.deleteRecursively()
                imported.deleteRecursively()

                context.sendProfileChanged(uuid)
            }
        }
    }

    suspend fun release(context: Context, uuid: UUID): Boolean {
        return withContext(NonCancellable) {
            profileLock.withLock {
                PendingDao().remove(uuid)

                context.pendingDir.resolve(uuid.toString()).deleteRecursively()
            }
        }
    }

    suspend fun active(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                if (ImportedDao().exists(uuid)) {
                    val store = ServiceStore(context)

                    store.activeProfile = uuid

                    context.sendProfileChanged(uuid)
                }
            }
        }
    }

    private fun Pending.enforceFieldValid() {
        val scheme = Uri.parse(source)?.scheme?.lowercase(Locale.getDefault())

        when {
            name.isBlank() -> throw IllegalArgumentException("Empty name")

            source.isEmpty() && type != Profile.Type.File -> throw IllegalArgumentException("Invalid url")

            source.isNotEmpty() && scheme != "https" && scheme != "http" && scheme != "content" -> throw IllegalArgumentException(
                "Unsupported url $source"
            )

            interval != 0L && TimeUnit.MILLISECONDS.toMinutes(interval) < 15 -> throw IllegalArgumentException("Invalid interval")
        }
    }

}
