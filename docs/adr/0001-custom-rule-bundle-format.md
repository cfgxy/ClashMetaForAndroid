# ADR 0001：自定义规则包格式（Android 侧）

- 状态：已采纳
- 日期：2026-09-26
- 编号说明：与 clash-verge-rev 的 `docs/adr/0001-custom-rule-bundle-format.md` 使用同一编号——
  两端共用同一份格式契约，编号对齐是为了让任何一端的读者都能直接找到对方的记录。
  **格式契约本身以 clash-verge-rev 的那份 ADR 为准**；本文件只记录 Android 侧的落点与实现选择，
  两端如出现语义冲突，按冲突处理而不是各自解释。

## 背景

用户在订阅之上自己维护的规则无法在设备之间、也无法在本客户端与 PC 端之间迁移。可迁移的只有
用户自有的那一层：订阅会持续更新自己的 `rules` 与 `rule-providers`，把它们打包既重复又会在导入
时覆盖订阅方的更新。

Android 侧「我们自己定义的那一层」的物理落点是两张 Room 表（RUYI-176 引入）：

- `rule_override` —— 用户自定义规则，含 `position`（PREPEND / APPEND）、类型、内容、策略、排序；
- `rule_provider` —— 用户声明的规则集，含名称、类型、行为、格式、url、更新间隔。

订阅自带的规则不在这两张表里，它们随订阅文件本身更新，因此**天然不会被打包**——不需要额外的
过滤逻辑来保证这一点。

## 决策

导出与导入同一个 ZIP：只装用户自有层（规则序列与规则集声明）。规则集只传声明（名称、url、
behavior、interval），从不传已下载的内容——内容由各端内核按 url 自行抓取。

订阅自身的 `rules` 与 `rule-providers` 不读、不打包、不修改。

### 包布局

```text
manifest.json
rules/sequence.yaml       # prepend / append / delete，保持顺序
providers/providers.yaml  # rule-providers 声明，不含内容
```

`delete` 是格式的一部分：它记录用户抑制掉的订阅规则行。本端目前没有「抑制订阅自有规则」的能力，
因此导出时 `delete` 恒为空数组，导入时按条计数并在预检界面明示「已忽略 N 条删除条目」——
既不凭空造出该语义，也不静默丢弃对端写进来的内容。

### manifest.json

| 字段 | 含义 |
| --- | --- |
| `formatVersion` | `major.minor`，当前 `1.0` |
| `generator` | `{ app, version }`；本端 `app` 为 `clash-meta-for-android` |
| `createdAt` | RFC 3339 时间戳（UTC，毫秒） |
| `proxyPolicies` | 包内 `prepend`/`append` 规则引用到的线路名去重清单 |
| `contents` | 每个文件的 `path`、`sha256`、`entryCount` |

字段名与结构由 `BundleContractTest` 逐字段断言，改键名会直接让该测试失败。

### 版本兼容

major 不一致：拒绝整包，不写任何文件。minor 更高：放行，忽略本端不认识的字段，并在预检界面明确
告知「该包由更新的版本生成」。禁止静默丢字段。

### 导入语义

导入是原子的：完整解析、全量校验、所有用户决策都在第一次写入之前完成，落库走单个
`Database.database.withTransaction {}`。

- 逐字节相同的规则行跳过；导入的行追加在既有行之后。
- 包内线路名必须映射到本地线路名后才写入。候选 = 内置策略（`DIRECT`、`REJECT`、`REJECT-DROP`、
  `PASS`）+ 当前 profile 实际可用的策略组名；同名预选。未完成映射的规则一条都不写。
- 同名规则集由用户选择覆盖 / 跳过 / 改名。跳过时报告包内哪些规则引用了它（含嵌在
  `AND` / `OR` / `NOT` 里的引用），这些规则同样不导入；改名时同步改写 `RULE-SET,<name>` 引用。

### 安全

任何 entry 被查找之前先归一化其名字：绝对路径或任何 `..` 段一律拒绝**整包**。
`service/.../bundle/EntryPath.kt` 直接拒绝穿越段，不做 resolve 化解——合法包永远不需要这两种形式，
resolve 只会让恶意名字被接受。异常不携带肇事条目名，避免把攻击者构造的字符串回显给用户。

规则集 `url` 可能内嵌订阅令牌：日志、提示与导出确认界面只输出 host，不输出完整 URL；导出界面
明确提示「包内含订阅地址」。

导入内容只是数据：解析为 YAML/JSON 后写入数据库，不进入任何执行路径。

另设三道体量上限防 zip bomb：至多 64 个 entry、单文件 4 MiB、解压后合计 16 MiB，边读边计数，
不信任 `ZipEntry.getSize()` 的自述值。

## 与 PC 端的差异（均不改变格式契约）

| 项 | PC（clash-verge-rev） | Android | 说明 |
| --- | --- | --- | --- |
| 写入压缩方式 | store | deflate | 双方读侧都同时支持 store 与 deflate |
| 规则集 `path` | 可能带 | 不带 | `path` 由各端按自身目录布局派生，进包等于把一端的目录结构强加给另一端；ADR 字段表中规则集声明本就不含 `path` |
| 体量上限拒绝 | 无 | `TooLarge` | 本端多出的一类拒绝原因，属实现侧防护，不新增也不改变任何包内字段 |

## 影响

- 包体小，且不随订阅更新而变化。
- 跨端互通只依赖两份 YAML 与 manifest。
- 包不是备份：导入到没有对应策略组的 profile 时必须经过映射这一步。
- ZIP 层用 JDK 内置 `java.util.zip`，不引入新的压缩依赖。
