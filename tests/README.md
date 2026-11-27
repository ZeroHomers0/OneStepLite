# JVM 测试分层

`test.ps1` 先编译完整生产源码，再按依赖层级运行测试：

| 目录 | 职责 | 约束 |
| --- | --- | --- |
| `unit/` | 纯策略、几何与状态机 | 测试源码不得直接引用 `android.*` 或 Xposed API |
| `contract/` | 跨进程动作、字段和不可变信号 | 只依赖纯 Java 契约，保持线上字符串兼容 |
| `android/` | 需要 Android API 类型参与编译的策略 | 只验证 JVM 可证明的逻辑，不代替真机验收 |
| `../tools/*-jvm/` | 从生产源码切片回放 | 验证真实方法体、所有权与事务顺序 |

契约层本身位于 `app/src/main/java/io/github/zerohomers0/onesteplite/contract/`，不得引用 Android 或 Xposed。新增测试按上表放入对应层；新增生产类被切片套件编译时，仍须逐个补入相应 `javac` 源文件列表。