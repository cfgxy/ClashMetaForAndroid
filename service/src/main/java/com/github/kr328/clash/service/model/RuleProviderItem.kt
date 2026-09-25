@file:UseSerializers(UUIDSerializer::class)

package com.github.kr328.clash.service.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import com.github.kr328.clash.service.util.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.*

/**
 * 跨进程传输用的规则集条目（图形界面 <-> IProfileManager），
 * 与 Room 实体 data.RuleProvider 分离，理由同 [RuleOverrideItem]。
 */
@Serializable
data class RuleProviderItem(
    val id: UUID,
    val profileUuid: UUID,
    val name: String,
    val type: RuleProviderType,
    val behavior: RuleProviderBehavior,
    val format: RuleProviderFormat,
    val url: String,
    val updateInterval: RuleProviderUpdateInterval,
    val sortOrder: Long,
    // 该规则集当前是否被至少一条 RULE-SET 规则引用——由服务层按裁定一的 SQL 口径实时计算，
    // 非持久化列，供列表页与删除确认弹窗判断是否走强提示分支。
    val referenced: Boolean = false,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<RuleProviderItem> {
        override fun createFromParcel(parcel: Parcel): RuleProviderItem {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<RuleProviderItem?> {
            return arrayOfNulls(size)
        }
    }
}
