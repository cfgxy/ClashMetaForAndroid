package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.data.RuleOverride
import com.github.kr328.clash.service.data.RuleOverrideDao
import com.github.kr328.clash.service.data.RuleProvider
import com.github.kr328.clash.service.data.RuleProviderDao
import com.github.kr328.clash.service.data.toEntity
import com.github.kr328.clash.service.model.CustomRule
import com.github.kr328.clash.service.model.CustomRuleProvider
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.RulePosition
import com.github.kr328.clash.service.model.RuleOverrideItem
import com.github.kr328.clash.service.model.RuleProviderBehavior
import com.github.kr328.clash.service.model.RuleProviderFormat
import com.github.kr328.clash.service.model.RuleProviderItem
import com.github.kr328.clash.service.model.RuleProviderSyntaxException
import com.github.kr328.clash.service.model.RuleProviderType
import com.github.kr328.clash.service.model.RuleProviderUpdateInterval
import com.github.kr328.clash.service.model.RuleProviderValidationField
import com.github.kr328.clash.service.model.RuleSyntaxException
import com.github.kr328.clash.service.model.RuleType
import com.github.kr328.clash.service.model.RuleValidationField
import com.github.kr328.clash.service.model.validate
import com.github.kr328.clash.service.override.RuleOverrideException
import com.github.kr328.clash.service.override.RuleOverrideFailureTracker
import com.github.kr328.clash.service.override.RuleProviderReferencedException
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.directoryLastModified
import com.github.kr328.clash.service.util.generateProfileUUID
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.*

class ProfileManager(private val context: Context) : IProfileManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)

    init {
        launch {
            Database.database //.init

            ProfileReceiver.rescheduleAll(context)
        }
    }

    override suspend fun create(type: Profile.Type, name: String, source: String, ageSecretKey: String?): UUID {
        val uuid = generateProfileUUID()
        val pending = Pending(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            interval = 0,
            upload = 0,
            total = 0,
            download = 0,
            expire = 0,
            ageSecretKey = ageSecretKey,
        )

        PendingDao().insert(pending)

        context.pendingDir.resolve(uuid.toString()).apply {
            deleteRecursively()
            mkdirs()

            @Suppress("BlockingMethodInNonBlockingContext")
            resolve("config.yaml").createNewFile()
            resolve("providers").mkdir()
        }

        return uuid
    }

    override suspend fun clone(uuid: UUID): UUID {
        val newUUID = generateProfileUUID()

        val imported = ImportedDao().queryByUUID(uuid)
            ?: throw FileNotFoundException("profile $uuid not found")

        val pending = Pending(
            uuid = newUUID,
            name = imported.name,
            type = Profile.Type.File,
            source = imported.source,
            interval = imported.interval,
            upload = imported.upload,
            total = imported.total,
            download = imported.download,
            expire = imported.expire,
            ageSecretKey = imported.ageSecretKey
        )

        cloneImportedFiles(uuid, newUUID)

        PendingDao().insert(pending)

        return newUUID
    }

    override suspend fun patch(uuid: UUID, name: String, source: String, interval: Long, ageSecretKey: String?) {
        val pending = PendingDao().queryByUUID(uuid)

        if (pending == null) {
            val imported = ImportedDao().queryByUUID(uuid)
                ?: throw FileNotFoundException("profile $uuid not found")

            cloneImportedFiles(uuid)

            PendingDao().insert(
                Pending(
                    uuid = imported.uuid,
                    name = name,
                    type = imported.type,
                    source = source,
                    interval = interval,
                    upload = 0,
                    total = 0,
                    download = 0,
                    expire = 0,
                    ageSecretKey = ageSecretKey,
                )
            )
        } else {
            val newPending = pending.copy(
                name = name,
                source = source,
                interval = interval,
                upload = 0,
                total = 0,
                download = 0,
                expire = 0,
                ageSecretKey = ageSecretKey,
            )

            PendingDao().update(newPending)
        }
    }

    override suspend fun update(uuid: UUID) {
        scheduleUpdate(uuid, true)
    }

    override suspend fun commit(uuid: UUID, callback: IFetchObserver?) {
        ProfileProcessor.apply(context, uuid, callback)

        scheduleUpdate(uuid, false)
    }

    override suspend fun release(uuid: UUID) {
        ProfileProcessor.release(context, uuid)
    }

    override suspend fun delete(uuid: UUID) {
        ImportedDao().queryByUUID(uuid)?.also {
            ProfileReceiver.cancelNext(context, it)
        }

        ProfileProcessor.delete(context, uuid)
    }

    override suspend fun queryByUUID(uuid: UUID): Profile? {
        return resolveProfile(uuid)
    }

    override suspend fun queryAll(): List<Profile> {
        val uuids = withContext(Dispatchers.IO) {
            (ImportedDao().queryAllUUIDs() + PendingDao().queryAllUUIDs()).distinct()
        }

        return uuids.mapNotNull { resolveProfile(it) }
    }

    override suspend fun queryActive(): Profile? {
        val active = store.activeProfile ?: return null

        return if (ImportedDao().exists(active)) {
            resolveProfile(active)
        } else {
            null
        }
    }

    override suspend fun setActive(profile: Profile) {
        ProfileProcessor.active(context, profile.uuid)
    }

    override suspend fun queryRuleOverrides(uuid: UUID): List<RuleOverrideItem> {
        val failedIds = RuleOverrideFailureTracker.queryFailedIds(uuid)

        return RuleOverrideDao().queryByProfile(uuid).map { it.toItem(applyFailed = it.id in failedIds) }
    }

    override suspend fun addRuleOverride(
        uuid: UUID,
        ruleType: RuleType,
        content: String,
        policy: String,
        position: RulePosition,
    ): RuleOverrideItem {
        CustomRule(ruleType, content, policy, position).validate()
        validateRuleSetReference(uuid, ruleType, content)

        val maxSortOrder = RuleOverrideDao().queryMaxSortOrder(uuid, position)

        val entity = RuleOverride(
            id = UUID.randomUUID(),
            profileUuid = uuid,
            position = position,
            ruleType = ruleType.literal,
            content = content,
            policy = policy,
            sortOrder = maxSortOrder + 1,
        )

        RuleOverrideDao().insert(entity)

        return entity.toItem()
    }

    override suspend fun updateRuleOverride(item: RuleOverrideItem) {
        CustomRule(item.ruleType, item.content, item.policy, item.position).validate()
        validateRuleSetReference(item.profileUuid, item.ruleType, item.content)

        val existing = RuleOverrideDao().queryById(item.id)
            ?: throw RuleOverrideException("自定义规则不存在：${item.id}")

        RuleOverrideDao().update(
            existing.copy(
                position = item.position,
                ruleType = item.ruleType.literal,
                content = item.content,
                policy = item.policy,
            )
        )
    }

    override suspend fun deleteRuleOverride(id: UUID) {
        RuleOverrideDao().remove(id)
    }

    override suspend fun restoreRuleOverride(item: RuleOverrideItem) {
        // 撤销删除与新增/编辑同口径：期间规则集可能已被「清空引用并删除」移除，
        // 此时原样还原会写回一条指向不存在规则集的 RULE-SET 规则。
        validateRuleSetReference(item.profileUuid, item.ruleType, item.content)

        RuleOverrideDao().insert(item.toEntity())
    }

    override suspend fun queryRuleProviders(uuid: UUID): List<RuleProviderItem> {
        val dao = RuleProviderDao()

        return dao.queryByProfile(uuid).map {
            it.toItem(referenced = dao.countReferences(uuid, it.name) > 0)
        }
    }

    override suspend fun addRuleProvider(
        uuid: UUID,
        name: String,
        type: RuleProviderType,
        behavior: RuleProviderBehavior,
        format: RuleProviderFormat,
        url: String,
        updateInterval: RuleProviderUpdateInterval,
    ): RuleProviderItem {
        CustomRuleProvider(name, type, behavior, format, url, updateInterval).validate()

        val dao = RuleProviderDao()

        if (dao.queryByName(uuid, name) != null) {
            throw RuleProviderSyntaxException(RuleProviderValidationField.NAME, "规则集名称在当前配置内已存在：$name")
        }

        val maxSortOrder = dao.queryMaxSortOrder(uuid)

        val entity = RuleProvider(
            id = UUID.randomUUID(),
            profileUuid = uuid,
            name = name,
            type = type.literal,
            behavior = behavior.literal,
            format = format.literal,
            url = url,
            updateIntervalSeconds = updateInterval.seconds,
            sortOrder = maxSortOrder + 1,
        )

        dao.insert(entity)

        return entity.toItem()
    }

    override suspend fun updateRuleProvider(item: RuleProviderItem) {
        CustomRuleProvider(item.name, item.type, item.behavior, item.format, item.url, item.updateInterval).validate()

        val dao = RuleProviderDao()
        val existing = dao.queryById(item.id)
            ?: throw RuleOverrideException("规则集不存在：${item.id}")

        dao.queryByName(item.profileUuid, item.name)?.let {
            if (it.id != item.id) {
                throw RuleProviderSyntaxException(RuleProviderValidationField.NAME, "规则集名称在当前配置内已存在：${item.name}")
            }
        }

        dao.update(
            existing.copy(
                name = item.name,
                type = item.type.literal,
                behavior = item.behavior.literal,
                format = item.format.literal,
                url = item.url,
                updateIntervalSeconds = item.updateInterval.seconds,
            )
        )
    }

    override suspend fun deleteRuleProvider(id: UUID, force: Boolean) {
        val dao = RuleProviderDao()
        val existing = dao.queryById(id)
            ?: throw RuleOverrideException("规则集不存在：$id")

        val referenceCount = dao.countReferences(existing.profileUuid, existing.name)

        if (referenceCount > 0) {
            if (!force) {
                throw RuleProviderReferencedException(
                    referenceCount,
                    "规则集「${existing.name}」仍被 $referenceCount 条自定义规则引用，无法直接删除",
                )
            }

            dao.clearReferences(existing.profileUuid, existing.name)
        }

        dao.remove(id)
    }

    /**
     * RULE-SET 规则的语义校验：content 必须真实指向该 profile 已声明的规则集。
     * [CustomRule.validate] 只做字符集校验（纯 Kotlin，不依赖 Room），
     * 存在性校验放在此处（有 DB 访问能力的服务层），从而在结构上保证
     * 「GUI 下拉选择」与「服务层落库」两条路径都无法引用未声明的规则集。
     */
    private suspend fun validateRuleSetReference(profileUuid: UUID, ruleType: RuleType, content: String) {
        if (ruleType != RuleType.RULE_SET) return

        if (RuleProviderDao().queryByName(profileUuid, content) == null) {
            throw RuleSyntaxException(RuleValidationField.CONTENT, "规则集不存在：$content")
        }
    }

    private suspend fun resolveProfile(uuid: UUID): Profile? {
        val imported = ImportedDao().queryByUUID(uuid)
        val pending = PendingDao().queryByUUID(uuid)

        val active = store.activeProfile
        val name = pending?.name ?: imported?.name ?: return null
        val type = pending?.type ?: imported?.type ?: return null
        val source = pending?.source ?: imported?.source ?: return null
        val interval = pending?.interval ?: imported?.interval ?: return null
        val upload = pending?.upload ?: imported?.upload ?: return null
        val download = pending?.download ?: imported?.download ?: return null
        val total = pending?.total ?: imported?.total ?: return null
        val expire = pending?.expire ?: imported?.expire ?: return null

        return Profile(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            active = active != null && imported?.uuid == active,
            interval = interval,
            upload = upload,
            download = download,
            total = total,
            expire = expire,
            updatedAt = resolveUpdatedAt(uuid),
            imported = imported != null,
            pending = pending != null,
            ageSecretKey = if (pending != null) pending.ageSecretKey else imported?.ageSecretKey,
        )
    }

    private fun resolveUpdatedAt(uuid: UUID): Long {
        return context.pendingDir.resolve(uuid.toString()).directoryLastModified
            ?: context.importedDir.resolve(uuid.toString()).directoryLastModified
            ?: -1
    }

    private fun cloneImportedFiles(source: UUID, target: UUID = source) {
        val s = context.importedDir.resolve(source.toString())
        val t = context.pendingDir.resolve(target.toString())

        if (!s.exists())
            throw FileNotFoundException("profile $source not found")

        t.deleteRecursively()

        s.copyRecursively(t)
    }

    private suspend fun scheduleUpdate(uuid: UUID, startImmediately: Boolean) {
        val imported = ImportedDao().queryByUUID(uuid) ?: return

        if (startImmediately) {
            ProfileReceiver.schedule(context, imported)
        } else {
            ProfileReceiver.scheduleNext(context, imported)
        }
    }
}
