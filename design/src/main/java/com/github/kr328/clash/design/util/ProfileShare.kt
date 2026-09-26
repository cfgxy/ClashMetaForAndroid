package com.github.kr328.clash.design.util

import com.github.kr328.clash.service.model.Profile

/**
 * 配置分享内容的取值规则。
 *
 * 仅订阅（[Profile.Type.Url]）类型的配置持有可被其他设备导入的订阅地址，
 * 其取值即 [Profile.source] 原值（去除两端空白），与 CMFA 自身扫码导入
 * `createProfileByQrCode` 的入参契约保持一致，不包裹任何自定义 scheme。
 *
 * 订阅地址常含用户 token，属敏感信息：本对象只在内存中返回原值，不落盘、不记日志。
 */
object ProfileShare {
    private val SUPPORTED_SCHEMES = listOf("http://", "https://")

    /**
     * 返回该配置可用于生成二维码的内容；不可分享时返回 null。
     */
    @JvmStatic
    fun shareContentOf(profile: Profile): String? {
        if (profile.type != Profile.Type.Url) {
            return null
        }

        val source = profile.source.trim()

        if (source.isEmpty()) {
            return null
        }

        if (SUPPORTED_SCHEMES.none { source.startsWith(it, ignoreCase = true) }) {
            return null
        }

        return source
    }

    /**
     * 该配置是否具备分享二维码的条件，用于控制菜单项可见性。
     */
    @JvmStatic
    fun isShareable(profile: Profile): Boolean {
        return shareContentOf(profile) != null
    }
}
