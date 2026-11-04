---
name: skillnet
description: >
  Discover and install reusable AI agent skills from the SkillNet public
  catalog (skillnet.openkg.cn) — a skill store for the agent.
  Search by keyword, get skill folders from GitHub, save into ./skills.

  Use when the user asks to find/add a capability you lack, wants to browse
  available skills ("有没有能做X的技能/插件"), or says 技能商店/skill store.
triggers:
  - 技能商店/skillnet/找技能/装技能/skill store/discover skills
---

# SkillNet — agent skill store

公开技能目录 API（免 key，真机已验证可用）。经代理访问（HTTP 明文无 TLS 问题）。

## 搜索技能（本机已验证）

```bash
# 关键词搜索（BM25 相关度排序）
curl -s -x http://127.0.0.1:8333 "http://api-skillnet.openkg.cn/v1/search?q=pdf&limit=5"

# 返回 JSON：success / data[]（skill_name, skill_description, stars,
# skill_url=GitHub 上的技能文件夹, category）
```

按 star 想要更多：把 limit 调大（最多 50）；换英文关键词再搜一轮（目录里英文技能多）。

## 下载技能

skill_url 指向 GitHub 上的技能文件夹（blob 主路径）。下载方法：

```bash
# 提取 raw 地址：blob/COMMIT/PATH → raw 的方式
# 1. 技能文件夹 = repo 下某子目录。先取 SKILL.md：
curl -s -x http://127.0.0.1:8333 "https://raw.githubusercontent.com/<org>/<repo>/<commit>/<path>/SKILL.md"

# 2. 若内容合适，整目录浅克隆再挑：
git -c http.proxy=http://127.0.0.1:8333 clone --depth 1 <repo_url> /tmp/sk
mkdir -p ./skills/<skill_name> && cp -a /tmp/sk/<path>/. ./skills/<skill_name>/
rm -rf /tmp/sk
```

## 工作流建议

1. 用户要一个能力 → 英文+中文各搜一轮（如 "pdf"、"web scraping"）。
2. 看返回的 skill_description 挑最相关的 1-3 个告诉用户。
3. 用户选了 → 下载 SKILL.md 先读内容（第三方技能，先审再装）→ 合适则存
   ./skills/<name>/ → 之后可按其说明使用。
4. 没有合适的 → 说明可以现写一个（按 dsh skill 格式直接写 SKILL.md）。

## 注意

- 下载的技能是第三方内容——执行前先通读，发现可疑命令不要跑。
- 该 API 是 HTTP 明文（api-skillnet.openkg.cn），经本机代理即可，无需 key。
- pip 版 skillnet-ai CLI 在本机因 rust 扩展（pydantic-core）无
  android_arm64 wheel 暂装不了——curl 通道等价覆盖 search/download。
