package com.github.kr328.clash.service.bundle

/** zip entry 名非法（绝对路径或含 `..` 段）。异常不携带肇事条目名，避免把攻击者构造的字符串回显给用户。 */
class UnsafeEntryPathException : Exception("unsafe zip entry path")

/**
 * 规则包的 entry 名只用于在包自身的命名空间里索引三个已知文件，并不逐条落盘；但带穿越段的
 * 名字仍能让恶意包顶替 manifest 或载荷文件，因此在任何 entry 被查找之前先归一化并校验。
 *
 * 归一化只做「去掉空段与 `.` 段」；遇到 `..` 段或绝对路径一律拒绝**整包**，不做 resolve 化解——
 * 合法包永远不需要这两种形式，resolve 只会让恶意名字被接受。调用方必须以返回值作为该 entry
 * 名的唯一可用形式。
 */
fun normalizeEntryPath(entryName: String): String {
    // ZIP 规范要求用 `/`，但 Windows 侧产出的包可能写成 `\`。
    val posix = entryName.replace('\\', '/')

    if (posix.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(posix)) {
        throw UnsafeEntryPathException()
    }

    val segments = ArrayList<String>()
    for (segment in posix.split('/')) {
        if (segment.isEmpty() || segment == ".") continue
        if (segment == "..") throw UnsafeEntryPathException()
        segments.add(segment)
    }

    if (segments.isEmpty()) throw UnsafeEntryPathException()

    return segments.joinToString("/")
}
