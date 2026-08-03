# Learnings

Corrections, insights, and knowledge gaps captured during development.

**Categories**: correction | insight | knowledge_gap | best_practice

---

## [LRN-20260803-001] correction

**Logged**: 2026-08-03T22:40:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: config

### Summary
仓库名拼写错误：用户创建仓库时误写为 RESERVE_WEB，实际应为 REVERSE_WEB。

### Details
- 初始仓库 `SparkofSpike/RESERVE_WEB`（私有空仓库）已在本会话克隆并推送了初始提交（main 分支）。
- 用户指出拼写错误，使用 `gh repo rename REVERSE_WEB --repo SparkofSpike/RESERVE_WEB` 重命名远程仓库。
- 本地 remote URL 已更新为 `https://github.com/SparkofSpike/REVERSE_WEB.git`，fetch 验证通过。
- 旧链接自动 301 跳转到新地址，不影响已有 clone。

### Suggested Action
已解决。本地工作区目录名 `E:\Coding\Github\Reserve_Web` 仍为旧名，若用户需要保持一致可重命名（会改变工作区路径，需谨慎）。

### Metadata
- Source: user_feedback
- Related Files: .git/config
- Tags: github, rename, repo

---
