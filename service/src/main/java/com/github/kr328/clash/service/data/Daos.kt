package com.github.kr328.clash.service.data

fun ImportedDao(): ImportedDao {
    return Database.database.openImportedDao()
}

fun PendingDao(): PendingDao {
    return Database.database.openPendingDao()
}

fun SelectionDao(): SelectionDao {
    return Database.database.openSelectionProxyDao()
}

fun RuleOverrideDao(): RuleOverrideDao {
    return Database.database.openRuleOverrideDao()
}

fun RuleProviderDao(): RuleProviderDao {
    return Database.database.openRuleProviderDao()
}