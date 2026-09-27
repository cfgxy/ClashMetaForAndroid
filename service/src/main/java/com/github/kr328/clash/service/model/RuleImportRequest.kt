package com.github.kr328.clash.service.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

/**
 * 一次规则包导入要写入的全部内容，跨进程传给 [com.github.kr328.clash.service.remote.IProfileManager]。
 *
 * 全部校验（包格式、线路名映射、同名规则集决策）都在调用方完成，服务层只负责在**单个事务内**
 * 把这份已定稿的清单落库——要么全写成功，要么一条都不落。
 */
@Serializable
data class RuleImportRequest(
    /** 要新增或覆盖的规则集声明；名字已是最终名字（改名已在计划阶段完成）。 */
    val providers: List<CustomRuleProvider>,
    /** 要追加的规则；policy 已是本地线路名，RULE-SET 的 content 已改写为最终规则集名。 */
    val rules: List<CustomRule>,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<RuleImportRequest> {
        override fun createFromParcel(parcel: Parcel): RuleImportRequest =
            Parcelizer.decodeFromParcel(serializer(), parcel)

        override fun newArray(size: Int): Array<RuleImportRequest?> = arrayOfNulls(size)
    }
}

/** 导入实际落库的条数，用于界面回显结果。 */
@Serializable
data class RuleImportResult(
    val providersInserted: Int,
    val providersOverwritten: Int,
    val rulesInserted: Int,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<RuleImportResult> {
        override fun createFromParcel(parcel: Parcel): RuleImportResult =
            Parcelizer.decodeFromParcel(serializer(), parcel)

        override fun newArray(size: Int): Array<RuleImportResult?> = arrayOfNulls(size)
    }
}
