

```
java -jar FeatureStatistics-1.0-SNAPSHOT.jar -t JIT_Type/

java -jar FeatureStatistics-1.0-SNAPSHOT.jar -t Javac_Bugs_Type/

python3 aiQuery_jit.py --dir ../new_crawl/Hotspot_filtered/ --stage analyze --model deepseek --workers 10

python3 analyze_jit_llm_results.py --dir JIT_Type/ --model deepseek

python3 crawl_hotspot.py --start-date 1999-07-06 --end-date 2026-08-28 --workers 15

```





# TypeFuzz

TypeFuzz 是一个面向 JVM 类型相关缺陷的程序变异与差分测试工具。

本文档是 TypeFuzz 的使用入口，面向第一次接触该工具的使用者。请从项目根目录执行文档中的命令，即包含根 `pom.xml` 的目录。

## 1. 工具简介



### 1.3 项目结构

```text
TypeFuzz/
├── pom.xml
├── difftest-core/       # 差分测试核心模块
├── fuzz-app/            # 模糊测试主程序
├── seed-filter/         # 种子筛选模块
├── fuzz-app/src/main/resources/fuzz.yaml
├── seed-filter/src/main/resources/seed-filter.yaml
└── difftest-core/src/main/resources/difftest.yaml
```

<!-- TODO: 根据最终发布版本补充或修正目录说明。 -->

## 2. 系统要求

<!-- TODO: 填写支持的 Ubuntu / Java / Maven 版本。 -->

- 操作系统：<!-- TODO -->
- Java：<!-- TODO -->
- Maven：<!-- TODO -->
- 编译器和 JVM：<!-- TODO -->
- 其他外部依赖：<!-- TODO -->

## 3. 获取源码

<!-- TODO: 填写仓库地址、推荐分支或版本，以及源码获取命令。 -->

```bash
# TODO: 替换为正式仓库地址和版本
git clone <repository-url>
cd TypeFuzz
```

## 4. 编译项目

<!-- TODO: 说明编译前提，并补充首次编译的完整命令。 -->

```bash
mvn clean package -DskipTests
```

编译完成后，主要产物位于：

```text
fuzz-app/target/
difftest-core/target/
seed-filter/target/
```

<!-- TODO: 填写实际 JAR 文件名和各模块之间的关系。 -->

## 5. 快速开始

<!-- TODO: 提供一个最小、可独立运行的示例，包括输入种子、配置文件和预期现象。 -->

### 5.1 准备输入

```text
<!-- TODO: 填写示例种子目录和示例文件。 -->
```

### 5.2 启动 TypeFuzz

```bash
# TODO: 根据最终参数接口补充可直接运行的命令
java -jar fuzz-app/target/fuzz-app-1.0-SNAPSHOT.jar \
  --fuzzConfig fuzz.yaml \
  --filterConfig seed-filter.yaml \
  --difftestConfig difftest.yaml
```

### 5.3 运行缺陷复现模式

```bash
# TODO: 说明 -R 或其他复现模式参数的含义和使用条件
java -jar fuzz-app/target/fuzz-app-1.0-SNAPSHOT.jar \
  --fuzzConfig fuzz.yaml \
  --filterConfig seed-filter.yaml \
  --difftestConfig difftest.yaml \
  -R
```

## 6. 基本使用方法

<!-- TODO: 说明从准备种子到启动、停止和复现实验的标准操作流程。 -->

### 6.1 标准运行流程

```text
1. 准备 Java seed
2. 配置 seed-filter
3. 配置 fuzzing 参数
4. 配置编译器和 JVM 目标
5. 编译 TypeFuzz
6. 启动 fuzzing
7. 检查日志和输出
8. 复现可疑结果
```

### 6.2 命令行参数

| 参数 | 是否必需 | 说明 |
| --- | --- | --- |
| `--fuzzConfig` | TODO | 模糊测试配置文件 |
| `--filterConfig` | TODO | 种子筛选配置文件 |
| `--difftestConfig` | TODO | 差分测试配置文件 |
| `-R` | TODO | 缺陷复现模式 |

<!-- TODO: 根据 CLI 实际实现补充全部参数、默认值和示例。 -->

## 7. 配置文件

### 7.1 fuzz 配置

文件：

```text
fuzz-app/src/main/resources/fuzz.yaml
```

常用配置：

```yaml
FuzzingEngine.max_fuzzing_rounds: 10
FuzzingEngine.max_fuzzing_time_minutes: auto
FuzzingEngine.max_mutations_per_seed: 3
FuzzingEngine.max_mutation_depth: 10
```

<!-- TODO: 核对配置键名、默认值和 seed/output 路径配置。 -->

### 7.2 seed-filter 配置

文件：

```text
seed-filter/src/main/resources/seed-filter.yaml
```

<!-- TODO: 说明输入种子目录、筛选规则、缓存和报告配置。 -->

### 7.3 difftest 配置

文件：

```text
difftest-core/src/main/resources/difftest.yaml
```

常用配置：

```yaml
difftest.timeout_seconds: 15
difftest.output_limit_line: 2000
difftest.use_compiler_dt: true
difftest.ignore_timeout_difference: true
difftest.java_version: 8
difftest.active_compilers: javac-${difftest.java_version},ecj
difftest.active_jvms: hotspot-${difftest.java_version},openj9-${difftest.java_version}
```

<!-- TODO: 补充 JDK、编译器和 JVM 的路径配置示例。 -->

### 7.4 配置优先级与路径约定

<!-- TODO: 说明命令行参数、配置文件和默认值之间的覆盖关系。 -->
<!-- TODO: 统一相对路径、绝对路径和路径分隔符的写法。 -->

## 8. 运行模式

<!-- TODO: 介绍普通 fuzzing、compiler differential testing、JVM differential testing 和复现模式。 -->

| 模式 | 目的 | 入口或参数 | 适用场景 |
| --- | --- | --- | --- |
| TODO | TODO | TODO | TODO |

## 9. 种子、变异与目标配置

### 9.1 种子格式

<!-- TODO: 说明支持的 Java 文件结构、入口方法和辅助文件要求。 -->

### 9.2 变异算子

<!-- TODO: 列出主要变异算子及其启用方式。 -->

### 9.3 编译器与 JVM 目标

<!-- TODO: 说明可配置的 compiler/JVM target，以及如何添加或禁用目标。 -->

## 10. 开发者入口

<!-- TODO: 介绍如何添加新的变异算子、筛选规则、执行目标或分析器。 -->

### 10.1 模块职责

<!-- TODO -->

### 10.2 扩展点

<!-- TODO -->

### 10.3 测试与验证

<!-- TODO: 填写单元测试、集成测试和手工验证命令。 -->

