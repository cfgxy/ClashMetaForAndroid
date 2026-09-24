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
 * 跨进程传输用的自定义规则条目（图形界面 <-> IProfileManager）。
 * 与 Room 实体 data.RuleOverride 分离：该实体不是 Parcelable/Serializable，
 * 不能直接跨 Binder 传输，这里单独定义与其字段一一对应的传输对象。
 */
@Serializable
data class RuleOverrideItem(
    val id: UUID,
    val profileUuid: UUID,
    val position: RulePosition,
    val ruleType: RuleType,
    val content: String,
    val policy: String,
    val sortOrder: Long,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<RuleOverrideItem> {
        override fun createFromParcel(parcel: Parcel): RuleOverrideItem {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<RuleOverrideItem?> {
            return arrayOfNulls(size)
        }
    }
}
