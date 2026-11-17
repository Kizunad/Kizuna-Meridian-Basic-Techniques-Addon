# 仓库协作约定

- 简体中文沟通、文档和意图注释；遵守 `~/.codex/AGENTS.md` 的 Java 代码形状规则。
- 本仓是基础功法纯数据附属；正式 Fabric 包无 Java 入口、无类、无 Mixin。
- `shared/` 保存唯一 txtpb 目录，`fabric/` 放客户端资源；验证代码只在 `verification/`、`gametest/`。
- 依赖相邻功法库、Human 与 Framework 公开协议；不修改依赖仓库。
- 每个 Java 方法和构造器内部写中文意图注释。
- `docs/wiki/` 是文档维护源，README 只作入口。
- 交付运行文档与数据检查、`git diff --check`。
- 未经用户授权不提交、不推送、不暂存、不创建远端。
- 原创内容采用 PolyForm Noncommercial 1.0.0，保留第三方许可。

## 构建和测试

构建、测试一律走工作区的 `kz`（`~/Code/Kizuna-remote-session/scripts/kz`），不直接运行 `./gradlew`。细则以 `~/Code/Kizuna-remote-session/handoff/codex-worker-rules.md` 为准，动手前先读。

- 单测、构建和服务端 GameTest：`kz test ~/Code/<本仓的 worktree>`
- 要带上别的仓的 worktree 一起测：每个加一次 `--with ~/Code/<那个仓的 worktree>`
- 客户端测试：`kz client ~/Code/<本仓的 worktree>` 列出有哪些项，`kz client ~/Code/<本仓的 worktree> <项>` 跑其中一项
- 要自己写 Gradle 参数时：`kz gradle ~/Code/<本仓的 worktree> -- <参数>`
- 不提交、不推送；在自己的 worktree（`~/Code/wt-*`）里改，不改主目录。
