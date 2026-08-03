# RESERVE_WEB（代号：TEST）

赛博桌游战斗辅助工具（Web 版），替代传统文字跑团中的手动骰点与数值计算。

核心能力：双边回合制战斗裁决（速度裁定、伤害计算、演出触发）、账号体系、构筑管理、木桩战与战报统计。

- 规则蓝本：`TEST.游戏玩法.pdf`（本仓库根目录）
- 当前阶段：规则验证与框架搭建（Harness / 原型期），战斗模式为单人木桩战，PVP 留待后续

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Java 21 + Spring Boot 3.2 + Maven + Spring Security (JWT) + Spring Data JPA |
| 前端 | Vue 3 + Vite 5 + TypeScript + Naive UI + Pinia + Vue Router + Axios |
| 数据库 | H2 文件模式（`AUTO_SERVER=TRUE`），JPA 抽象，后续可切换 MySQL |
| 数据交换 | JSON，前后端严格遵循 DTO 契约 |

## 目录结构

```
backend/    # Spring Boot 后端（战斗状态机、裁决逻辑、账号与构筑）
frontend/   # Vue 3 前端（展示与指令转发，不含任何战斗裁决逻辑）
```

## 架构原则

- 前后端完全分离：所有胜负判定、伤害数值、随机骰子均由后端产出。
- 前端仅负责展示与指令转发，绝不触碰战斗裁决逻辑。
- 后端绝不关心前端渲染细节。

## 开发

```bash
# 后端（默认端口 5566，H2 数据文件位于 backend/data/）
cd backend
mvn spring-boot:run

# 前端（开发服务器默认 5173，代理 /api 到后端）
cd frontend
npm install
npm run dev
```

## Git 规范

- 原子化提交：一次提交，一个逻辑变更。
- Commit 信息遵循 Angular Convention，且描述使用纯英文：

```
<type>(<scope>): <subject>
```

- type：feat / fix / docs / style / refactor / test / chore
- scope 示例：combat、dice、auth、ui-log、api-dto
