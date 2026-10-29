# 仓库协作约定

- 简体中文沟通、文档和意图注释；遵守 `~/.codex/AGENTS.md` 的 Java 代码形状规则。
- 本仓是基础功法纯数据附属；正式 Fabric 包无 Java 入口、无类、无 Mixin。
- `shared/` 保存唯一 txtpb 目录，`fabric/` 放客户端资源；验证代码只在 `verification/`、`gametest/`。
- 依赖相邻功法库、Human 与 Framework 公开协议；不修改依赖仓库。
- 每个 Java 方法和构造器内部写中文意图注释。
- `docs/wiki/` 是文档维护源，README 只作入口。
- 交付运行 `./gradlew test build :gametest:runGametest`、文档与数据检查、`git diff --check`。
- 未经用户授权不提交、不推送、不暂存、不创建远端。
- 原创内容采用 PolyForm Noncommercial 1.0.0，保留第三方许可。
