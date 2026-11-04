---
name: skill-distill
description: >
  Distill reusable skills from completed work — the SkillX method
  (zjunlp/SkillX): after finishing a multi-step task, extract what worked
  into a three-tier skill (planning / functional / atomic) and save it as
  a local skill folder so future sessions start ahead.

  Use when the user says 沉淀一下/总结成技能/记住这套做法/turn this into a
  skill, or after a long successful task worth reusing.

  Powered by the local vLLM (qwen3.5-35b); no external APIs.
triggers:
  - 沉淀/总结成技能/记住做法/skill distill/extract skill
---

# Skill 蒸馏（SkillX 三层格式）

把刚做完的任务经验固化为可复用技能。核心思想来自 SkillX：经验以
**结构化三层技能**表示比原始轨迹/自由反思更可迁移。

## 三层技能格式

生成的技能文件夹放在 `./skills/<skill-name>/`：

```
skills/<skill-name>/
  SKILL.md          # 主文档：description + triggers + 正文
```

SKILL.md 正文按三层组织：

1. **Planning skill**（规划层）：这类任务怎么拆步骤、什么顺序、何时验证。
2. **Functional skills**（功能层）：可复用的多步子程序（工具组合套路）。
3. **Atomic skills**（原子层）：单个工具的使用要点、约束、坑（失败笔记）。

## 工作流

1. **收集轨迹**：本次会话中已完成的步骤（对话上下文里就有；也可读
   `$DSH_HOME` 下的 session jsonl 提取工具调用序列）。
2. **蒸馏**（用本地模型或自己写）：
   ```bash
   skillnet create --from session -o ./skills/<name>
   ```
   或直接手写 SKILL.md（小任务更快）。
3. **质量自检**（SkillX Filter 的便携性标准）：
   - 不含本环境特有的一次性路径/ID
   - 原子层记录了真实的失败与绕法
   - 规划层不绑定具体模型名
4. **登记**：放到 ./skills 后告知用户可用 @技能名 引用。

## 蒸馏提示模板（供本地模型用）

```
从以下任务轨迹中提取三层技能（planning/functional/atomic）。
要求：planning 给步骤骨架；functional 给可复用工具组合；
atomic 给每个工具的约束与踩坑。输出为 SKILL.md 的 markdown 正文。
轨迹：
<traj>
```

## 与 SkillNet 的配合

- 蒸馏出的技能可推到 GitHub 后用 `skillnet` 登记（社区共享）。
- 本地技能积累多了，可用 `skillnet analyze` 建技能关系图（需配
  embedding，暂未配）。
