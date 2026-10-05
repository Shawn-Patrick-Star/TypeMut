# TypeFuzz HotSpot LLM 分析工具

本目录用于对已经收集的 HotSpot JIT 编译器缺陷进行两阶段 LLM 分析：

1. `screen`：高召回率筛选程序类型是否对缺陷的触发、复现或表现存在实质参与；
2. `analyze`：重新判断类型参与，并优先利用 fixing patch 定位编译器组件、主要优化、交互优化和根因证据。

这里的 **type-related 不要求“类型本身是最终根因”**。只要某个具体类型选择、类型信息或类型相关语义对 bug 的触发、复现、compiler path、optimization eligibility 或故障表现有实质影响，就可以判为类型相关；仅仅因为 Java 程序天然包含类型则不算。

工具不会负责抓取 JDK issue，也不会运行 Java 测试用例。它读取每个缺陷目录中已有的 issue 元数据、Java 复现程序和修复 patch，压缩成受控长度的证据后提交给 OpenAI-compatible Chat Completions API。

## 1. 文件结构

| 文件 | 用途 |
|---|---|
| `aiQuery_jit.py` | 公共入口：执行两阶段 LLM 分析 |
| `analyze_jit_llm_results.py` | 公共入口：按官方 HotSpot taxonomy 统计结果并生成 CSV/JSON/LaTeX |
| `export_labeled_cases.py` | 公共入口：根据分析标签复制完整缺陷目录 |
| `tools/` | 内部实现：证据压缩、提示词、API 客户端和优化闭集 |
| `tests/` | 术语规范化、文件命名和批处理回退测试 |

顶层三个 Python 文件是面向用户的命令行入口。`tools` 中的模块由入口脚本复用，通常不需要直接运行或修改。

所有命令建议在本目录下运行：

```powershell
cd chapter2\LLManalyze
```

## 2. 运行环境

建议使用 Python 3.9 或更高版本。调用真实 API 时需要安装：

从 `02Tools/LLMAnalysis/` 目录安装 `02Tools` 下所有 Python 工具的依赖：

```powershell
python -m pip install -r ..\requirements.txt
```

`--dry-run` 和单元测试不会创建 API 客户端，因此不要求安装 API SDK，也不需要 API key。

## 3. 输入目录格式

`--dir` 指向缺陷语料根目录。每个直接子目录代表一个 case，例如：

```text
Hotspot_JIT_Bugs_v4/
├── JDK-8321234/
│   ├── JDK-8321234.json
│   ├── Test.java
│   └── fix.patch
└── JDK-8335678/
    ├── JDK-8335678.json
    └── compiler/
        └── TestBug.java
```

一个目录只有同时满足以下条件才会被处理：

- 存在 issue 元数据 JSON；优先使用 `<目录名>.json`；
- 至少存在一个 Java 文件；Java 文件会递归查找，但忽略 `.meta` 目录；
- patch 不是必需的；只读取 case 顶层的 `.patch` 和 `.diff` 文件。

如果找不到精确的 `<目录名>.json`，工具会尝试选择文件名以 case 基础 ID 开头的 JSON，同时排除 `llm_*`、`*_type*` 和 `*jit_opt*` 结果文件。

## 4. 配置 API

内置平台及默认配置如下：

| `--platform` | `--model` 别名 | 实际模型 ID | 默认 API key 环境变量 |
|---|---|---|---|
| `deepseek` | `deepseek` | `deepseek-v4-flash` | `DEEPSEEK_API_KEY` |
| `kimi` | `kimi` | `moonshot-v1-32k` | `KIMI_API_KEY` |
| `aliyun` | `qwen` | `qwen-max` | `ALIYUN_API_KEY` |
| `siliconflow` | `qwen` | `Qwen/Qwen3-32B` | `SILICONFLOW_API_KEY` |
| `siliconflow` | `deepseek` | `deepseek-ai/DeepSeek-V3.2` | `SILICONFLOW_API_KEY` |
| `modelscope` | `deepseek` | `deepseek-ai/DeepSeek-V3.2` | `MODELSCOPE_API_KEY` |
| `modelscope` | `qwen` | `Qwen/Qwen3-235B-A22B` | `MODELSCOPE_API_KEY` |

PowerShell 设置 API key：

```powershell
$env:DEEPSEEK_API_KEY = "your-api-key"
```

Bash 设置 API key：

```bash
export DEEPSEEK_API_KEY="your-api-key"
```

使用自定义 OpenAI-compatible 服务：

```powershell
$env:MY_LLM_API_KEY = "your-api-key"

python aiQuery_jit.py `
  --dir Hotspot_JIT_Bugs_v4 `
  --stage screen `
  --platform custom `
  --model my-model `
  --model-id provider/model-id `
  --base-url https://example.com/v1 `
  --api-key-env MY_LLM_API_KEY
```

这里 `--model` 是本地模型别名，也参与结果文件命名；`--model-id` 才是发送给服务端的实际模型 ID。

## 5. 推荐执行流程

### 5.1 先检查输入和预算

```powershell
python aiQuery_jit.py `
  --dir Hotspot_JIT_Bugs_v4 `
  --stage screen `
  --dry-run
```

`--dry-run` 不调用 API，只输出：

- 有效 case 数量；
- 已打包 case 数量；
- 包含 patch 的 case 数量；
- 证据字符数和粗略 token 估算；
- batch 数量；
- 最大的若干 case 及裁剪前后的大小。

### 5.2 第一阶段：高召回筛选

```powershell
python aiQuery_jit.py `
  --dir Hotspot_JIT_Bugs_v4 `
  --stage screen `
  --platform deepseek `
  --model deepseek `
  --workers 4
```

第一阶段处理所有有效 case，每个 case 生成：

```text
llm_deepseek_screen.json
```

结果示例：

```json
{
  "ID": "JDK-8321234",
  "Type_Related": "Yes",
  "Reason": "The faulty C2 reasoning depends on primitive width and sign extension."
}
```

`Type_Related` 只能是：

- `Yes`：证据支持类型对 bug 的触发、复现、compiler path、optimization eligibility 或故障表现存在实质参与；类型不必是最终根因；
- `No`：已有证据支持相关类型只是 testcase 的普通组成部分，对触发机制和故障表现没有实质参与；
- `Unclear`：存在类型参与的可能性，但当前压缩证据不足以确认或安全排除。

### 5.3 第二阶段：详细分析

```powershell
python aiQuery_jit.py `
  --dir Hotspot_JIT_Bugs_v4 `
  --stage analyze `
  --platform deepseek `
  --model deepseek `
  --workers 4
```

第二阶段只选择同一 `--model` 对应的第一阶段结果中标记为 `Yes` 或 `Unclear` 的 case。`No` 和缺少 screen 结果的 case 不会进入第二阶段。

每个 case 生成：

```text
llm_deepseek_type.json
```

详细结果的主要字段如下：

```json
{
  "ID": "JDK-8321234",
  "Type_Related": "Yes",
  "Type_Related_Reason": "...",
  "Type_Mechanism": "primitive width/sign propagation",
  "Compiler_Tier": "C2",
  "Faulty_Compiler_Component": "superword.cpp / SuperWord::transform_loop",
  "Primary_Faulty_Optimization": "Loop Vectorization",
  "Interacting_Optimizations": [
    "Loop Unrolling"
  ],
  "Optimization_Mapping_Reason": "...",
  "Optimization_Evidence_Basis": "Patch",
  "Optimization_Confidence": "High",
  "Optimization_Unknown_Reason": "N/A",
  "Root_Cause_In_Optimization": "...",
  "Java_Evidence": [],
  "Patch_Evidence": [],
  "Root_Cause_Summary": "...",
  "Alternative_Explanation": "None",
  "Uncertainty": "None"
}
```

注意：第二阶段会重新判断 `Type_Related`，不会盲目继承第一阶段结论。有 fixing patch 时，第二阶段会优先从 HotSpot compiler-source 修改中定位文件、类/phase、函数和错误语义，再将 implementation-level component 映射到官方优化 taxonomy。

## 6. HotSpot 优化名称规则

`Primary_Faulty_Optimization` 和 `Interacting_Optimizations` 受到代码层面的闭集约束。允许的名称来自 `hotspot_optimization_taxonomy.py` 中的 67 项官方优化。

规则如下：

- 主要优化必须是官方名称或 `Unknown`；
- 模型先识别 patch/issue/log 中的 HotSpot implementation-level compiler component，再基于该组件的语义映射到官方 taxonomy；不要求 patch 文本直接出现 taxonomy 名称；
- `Faulty_Compiler_Component` 保留实际文件、类、phase、函数或内部组件，避免 taxonomy 映射失败时丢失已识别的信息；
- 列表外的主要优化在保存前会变成 `Unknown`；
- 列表外的交互优化会被删除；
- 交互优化去重后最多保留 4 项；
- `Interacting_Optimizations` 不使用 `Unknown`，没有可靠证据时使用 `[]`；
- `Optimization_Evidence_Basis` 记录主要依据来自 Patch/Issue/Log/Testcase/Mixed；
- `Optimization_Confidence` 记录 High/Medium/Low；
- `Optimization_Unknown_Reason` 将 Unknown 区分为 `InsufficientEvidence` 和 `NoTaxonomyMatch`；
- 不允许仅凭 testcase 形状或宽泛文件名强行选择优化。

当前明确映射包括：

```text
SuperWord
SLP
C2 SuperWord
auto-vectorization
auto vectorization
    -> Loop Vectorization
```

映射只负责统一名称，不等同于根因证据。测试代码看起来适合向量化，并不足以判定 `Loop Vectorization`；issue、编译日志、断言或修复 patch 必须能证明 SuperWord/向量化实际参与了缺陷。对于 fixing patch，工具会优先保留 HotSpot compiler 文件，并在单个大文件内优先保留包含 `PhaseIdealLoop`、`SuperWord`、`TypeInt`、`ConnectionGraph`、unroll/peel/vector/inline/escape 等诊断术语的 hunk。

## 7. 断点续跑、重新分析和批处理

默认情况下，已有目标结果文件的 case 会被跳过。因此同一命令中断后可以直接重新执行。

强制删除当前阶段、当前模型的已有结果并重新分析：

```powershell
python aiQuery_jit.py `
  --dir Hotspot_JIT_Bugs_v4 `
  --stage analyze `
  --model deepseek `
  --clear
```

如果从旧版 prompt 升级到当前版本，**必须先重新运行 screen，再运行 analyze**。新的 type-related 定义允许“类型参与触发但不是最终根因”的 case；直接复用旧的 `screen=No` 会使这些 case 永远无法进入第二阶段。建议分别执行：

```powershell
python aiQuery_jit.py `
  --dir Hotspot_JIT_Bugs_v4 `
  --stage screen `
  --model deepseek `
  --clear

python aiQuery_jit.py `
  --dir Hotspot_JIT_Bugs_v4 `
  --stage analyze `
  --model deepseek `
  --clear
```

`--clear` 会删除所选 case 的对应结果文件。与 `--dry-run` 同时使用时不会删除文件。

常用控制参数：

| 参数 | 默认值 | 作用 |
|---|---:|---|
| `--workers` | `4` | 并发处理 batch 的线程数 |
| `--limit` | `0` | 只处理前 N 个选中 case；0 表示不限制 |
| `--batch-size` | screen=`5`，analyze=`2` | 每次 API 请求最多包含多少 case |
| `--max-batch-chars` | `80000` | 每个 batch 的最大证据字符数 |
| `--max-output-tokens` | screen=`2500`，analyze=`6000` | 单次响应的最大 token 数 |
| `--no-patches` | 关闭 | 不向 LLM 提供 patch 文件 |

少量 case 试运行：

```powershell
python aiQuery_jit.py `
  --dir Hotspot_JIT_Bugs_v4 `
  --stage screen `
  --limit 10 `
  --workers 1
```

如果 batch 响应缺少某个 ID、JSON 无法解析或 schema 不合法，工具会自动将受影响 case 改为单例请求重试。API 客户端本身对请求异常最多尝试 3 次。

## 8. 日志和失败排查

运行日志保存在语料根目录：

```text
run_llm_<model>_<stage>.log
```

例如：

```text
run_llm_deepseek_screen.log
run_llm_deepseek_analyze.log
```

每次启动会重新创建当前运行日志。日志包含 case 选择统计、batch 大小、token 使用量、缓存命中量、输出截断判断和最终 usage summary。

无法解析或无法通过校验的原始响应保存在：

```text
<语料根目录>/.llm_failed/screen/
<语料根目录>/.llm_failed/analyze/
```

某个 case 后续成功保存时，它之前对应的失败响应文件会被清除。

常见问题：

- `Environment variable ... is not set`：没有设置 `--api-key-env` 对应的环境变量；
- `No model id resolved`：平台与模型别名组合不存在，传入 `--model-id`；
- `No base URL resolved`：使用自定义平台时传入 `--base-url`；
- analyze 阶段选择 0 个 case：检查相同模型别名对应的 `llm_<model>_screen.json` 是否存在；
- 大量 `likely output-token truncation`：提高 `--max-output-tokens` 或降低 `--batch-size`；
- patch 没有进入证据：patch 必须位于 case 顶层，扩展名为 `.patch` 或 `.diff`。

## 9. 统计 LLM 分析结果

完成两个阶段后，可生成分布统计、阶段标签变化和论文表格：

```powershell
python analyze_jit_llm_results.py `
  --dir Hotspot_JIT_Bugs_v4 `
  --model deepseek
```

默认只将第二阶段 `Type_Related=Yes` 的 case 纳入优化统计。需要同时统计 `Yes` 和 `Unclear` 时：

```powershell
python analyze_jit_llm_results.py `
  --dir Hotspot_JIT_Bugs_v4 `
  --model deepseek `
  --labels Yes Unclear
```

默认输出目录为 `<语料根目录>/analysis_jit_llm/`。可以通过 `--output` 修改；通过 `--screen-file` 和 `--result-file` 可以读取自定义结果文件名。

主要输出包括：

- `summary.json`：总体统计汇总；
- `screen_to_analyze_transition.csv`：第一阶段到第二阶段的标签变化；
- `selected_cases.csv`：进入统计总体的 case 及详细字段；
- `compiler_tier_distribution.csv`：C1/C2/Shared/Unknown 分布；
- `primary_faulty_optimization_distribution.csv`：主要优化分布；对于原始 `Unknown/NoTaxonomyMatch`，统计阶段会先执行经过审计的 component→taxonomy 确定性映射；
- `component_postprocessed_cases.csv`：被统计阶段从 `Unknown/NoTaxonomyMatch` 映射为具体优化的 case 明细，保留原始标签、compiler component 和映射证据，便于审计；
- `component_postprocessed_distribution.csv`：上述确定性后处理映射到各优化的数量；
- `optimization_evidence_basis_distribution.csv`：优化判断主要证据来源分布；
- `optimization_confidence_distribution.csv`：优化判断置信度分布；
- `unknown_optimization_reason_distribution.csv`：Unknown 的原因分布（证据不足 / taxonomy 无匹配）；
- `interacting_optimization_distribution.csv`：交互优化分布；
- `involved_optimization_distribution.csv`：主要或交互优化的合并分布；
- `category_case_distribution.csv`：HotSpot 官方 9 类优化分布；
- `primary_category_distribution.csv`：主要优化对应的官方类别分布；
- `optimization_taxonomy_table.tex`：论文 LaTeX 表格；
- `unknown_primary_cases.csv`：主要优化为 `Unknown` 的完整 case 列表；
- `unknown_optimization_cases.csv`：与上表等价的显式 Unknown 优化明细；
- `unknown_notaxonomy_cases.csv`：仅包含 `Optimization_Unknown_Reason=NoTaxonomyMatch` 的 case，用于完善 component→taxonomy 映射；
- `unknown_insufficient_evidence_cases.csv`：仅包含 `Optimization_Unknown_Reason=InsufficientEvidence` 的 case，用于检查证据提取或 prompt 是否不足；
- `malformed_results.csv`：存在非法结果时生成。

对于已经生成的旧 LLM JSON，仅仅向 taxonomy/alias 表增加名称并不会改变原始 `Primary_Faulty_Optimization="Unknown"`。当前统计脚本会在且仅在原始结果为 `Unknown` 且 `Optimization_Unknown_Reason=NoTaxonomyMatch` 时，使用 `Faulty_Compiler_Component`、mapping reason 和 root-cause 字段进行保守的确定性二次映射；`InsufficientEvidence` 不会被自动改写。原始 JSON 不会被修改。

统计脚本与分析脚本共用 `tools/hotspot_optimization_taxonomy.py`。`CATEGORY_ORDER` 和 `CATEGORY_PASSES` 由该模块动态生成，覆盖官方 9 个类别、67 项优化；SuperWord 等术语也不会在后处理阶段重新产生另一套名称。

## 10. 导出分类后的完整 case

导出第二阶段判定为类型相关的 case：

```powershell
python export_labeled_cases.py `
  --dir Hotspot_JIT_Bugs_v4 `
  --output Hotspot_TypeRelated `
  --label related `
  --stage analyze `
  --model deepseek
```

可用标签：

| `--label` | 对应值 |
|---|---|
| `related` 或 `yes` | `Yes` |
| `unrelated` 或 `no` | `No` |
| `unclear` | `Unclear` |

导出工具默认跳过已经存在的目标 case。使用 `--overwrite` 才会替换目标目录，使用 `--dry-run` 可只查看选择结果。导出完成后会在目标目录生成 `export_manifest.json`。

如果结果文件使用了非默认名称，可以传入：

```powershell
python export_labeled_cases.py `
  --dir Hotspot_JIT_Bugs_v4 `
  --output SelectedCases `
  --label related `
  --result-file custom_result.json
```

## 11. 运行测试

```powershell
python -B -m unittest discover -s tests -v
```

当前测试覆盖：

- 结果文件名生成；
- SuperWord/SLP 到 `Loop Vectorization` 的规范化；
- 列表外优化的过滤；
- Unknown optimization reason 的一致性约束；
- patch hunk 对类型/优化诊断符号的优先保留；
- batch 缺失 case 时的单例回退。

测试不会调用真实 API。
