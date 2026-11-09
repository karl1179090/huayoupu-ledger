# Issue tracker: GitHub

本项目的 issue 与规格发布到用户自己的 fork：[karl1179090/huayoupu-ledger](https://github.com/karl1179090/huayoupu-ledger/issues)。使用 `gh` CLI。

## 目标仓库

- issue 操作显式指定 `--repo karl1179090/huayoupu-ledger`，API 路径使用 `repos/karl1179090/huayoupu-ledger`。
- 本地 `gh` 默认仓库设为该 fork；每个会话首次发布前用 `gh repo view --json nameWithOwner` 核对。目标不符时使用上述显式仓库参数。
- `origin` 指向上游，不作为本项目工作的默认 issue tracker。Git 的 fetch/push 远端及分支跟踪关系按现有配置保留。

## Issue 操作

- 创建：`gh issue create --repo karl1179090/huayoupu-ledger --title "<title>" --body-file <body-file>`。多行正文写入 UTF-8 文件后传入，保留换行。
- 读取：`gh issue view <number> --repo karl1179090/huayoupu-ledger --json number,title,body,labels,comments`。
- 列表：`gh issue list --repo karl1179090/huayoupu-ledger --state open --json number,title,body,labels`，按需要添加标签过滤。
- 修改正文：`gh issue edit <number> --repo karl1179090/huayoupu-ledger --body-file <body-file>`。
- 添加或移除标签：`gh issue edit <number> --repo karl1179090/huayoupu-ledger --add-label "<label>"` 或 `--remove-label "<label>"`。标签角色见 [triage-labels.md](triage-labels.md)。
- 评论：`gh issue comment <number> --repo karl1179090/huayoupu-ledger --body-file <body-file>`。
- 关闭：先发布说明，再调用 `gh issue close <number> --repo karl1179090/huayoupu-ledger`。已关闭 issue 的说明也单独作为评论发布。
- 发布前检查是否已有相同规格，避免因重试重复创建；写入后读取正文、标签及 URL 验证结果。

技能要求“publish to the issue tracker”时创建 GitHub issue；要求“fetch the relevant ticket”时读取对应 issue。规格由 `to-spec` 发布时应用映射后的 `ready-for-agent` 标签，无需额外分诊。

## 子任务与阻塞关系

优先使用 GitHub 原生子 issue 和依赖关系。先检查本机 `gh issue create --help` 与 `gh issue edit --help` 是否支持对应参数；支持时使用原生命令，否则使用 GitHub API：

- 添加子 issue：POST `repos/karl1179090/huayoupu-ledger/issues/<parent>/sub_issues`，参数 `sub_issue_id` 为子 issue 的数据库 ID。
- 添加阻塞项：POST `repos/karl1179090/huayoupu-ledger/issues/<child>/dependencies/blocked_by`，参数 `issue_id` 为阻塞 issue 的数据库 ID。
- 数据库 ID 从 `gh api repos/karl1179090/huayoupu-ledger/issues/<number> --jq .id` 取得，与 issue 编号及 node ID 不同。
- 无原生能力时，在子任务正文顶部使用 `Part of #<parent>`、`Blocked by: #<number>`，并在父任务维护任务列表。所有阻塞项关闭后才视为可开始。

## Pull requests as a triage surface

**PRs as a request surface: no.**

外部 PR 默认不进入需求分诊队列。用户以后可直接修改此配置。
