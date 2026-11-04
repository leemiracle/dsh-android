---
name: skillnet
description: >
  Discover, install, and manage reusable AI agent skills from the SkillNet
  public catalog (skillnet.openkg.cn) — a skill store for the agent.
  Search by keyword or natural-language intent, download skill folders from
  GitHub into the workspace, evaluate skill quality, and list what you have.

  Use when the user asks to find/add a capability you lack, wants to browse
  available skills ("有没有能做X的技能/插件"), or says 技能商店/skill store.

  Local model APIs are preconfigured: Chat Completions at the on-device
  vLLM (qwen3.5-35b) for create/evaluate; no external keys needed.
triggers:
  - 技能商店/skillnet/找技能/装技能/skill store/discover skills
---

# SkillNet — agent skill store

CLI: `skillnet` (installed in the Termux rootfs; run via the `linux` or
`bash` tool). Public search/download need no API key.

## 常用命令

```bash
# 关键词搜索（BM25，按相关度）
skillnet search "pdf" --limit 5

# 自然语言任务搜索（向量模式）
skillnet search "analyze financial PDF reports" --mode vector --limit 5

# 按 star 排序
skillnet search "web scraping" --sort-by stars

# 下载技能到工作区技能目录
skillnet download <skill_url> -d ./skills

# 列出本地技能
ls ./skills

# 评估技能质量（五维：安全/完整/可执行/可维护/成本）——走本地 vLLM
skillnet evaluate ./skills/<name>

# 诊断配置
skillnet doctor
```

## 环境说明（本机已预配）

- Chat API（create/evaluate 用）: `http://10.44.11.11:11433/v1`
  模型 `qwen3.5-35b`，key 任意非空值。若 skillnet 报配置缺失：
  ```bash
  skillnet config set api_key local-vllm-noauth
  skillnet config set base_url http://10.44.11.11:11433/v1
  skillnet config set model qwen3.5-35b
  ```
- GitHub 下载经代理通道，公开仓无需 token。
- 网络出问题先查：`echo $HTTPS_PROXY`（应为 http://127.0.0.1:8333）。

## 工作流建议

1. 用户要一个能力 → 先 `skillnet search`（关键词+向量两轮）看社区有没有现成的。
2. 有 → `download` 到 ./skills → 读它的 SKILL.md → 按其说明用。
3. 没有 → 告诉用户可以现写一个技能（按 dsh skill 格式）或用
   `skillnet create` 从文档/轨迹生成（走本地模型）。
4. 装好的技能目录在 ./skills 下持久保存；用户可在对话里 @ 引用。

## 注意

- 下载的技能是第三方代码/提示——执行前先读一遍内容再跑。
- analyze/route 需要 embedding 端点（本机未配），先只用 search/download/evaluate。
