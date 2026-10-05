# Tyche

Artifact for **“Understanding and Detecting Runtime Type Interaction Bugs in JIT Compilers.”**

This artifact contains:

- the Tyche implementation 
- the scripts used in the empirical study,
- the curated bug corpora 
- the filtered seed corpora used in the evaluation.

## Project Structure

```text
Tyche/
├── 01Code/                         Tyche implementation 
│   ├── fuzz-app/                   Fuzzing Pipeline
│   ├── seed-filter/                Seed filtering
│   ├── difftest-core/              JVM differential-testing engine
│   ├── fuzz.yaml                   
│   ├── seed-filter.yaml            
│   └── difftest.yaml               
├── 02Tools/
│   ├── CoverageCollector/          For collecting coverage
│   ├── Crawler/                    JBS issue crawlers
│   ├── FeatureStatistics/          Java AST feature extraction and statistics
│   └── LLMAnalysis/                LLM-assisted JIT-bug analysis
├── 03BugIssues/
│   ├── JIT_Type.zip                675 curated JIT type-related bug cases
│   └── Javac_Type.zip              719 curated javac type-related bug cases
└── 04Seeds/
    ├── TypeSeeds.zip               407 filtered TypeSeeds
    └── JFSeeds.zip                 663 filtered JavaFuzzeSeeds
```

## 01 Environment Setup

The reference experiments were conducted on Ubuntu 22.04. The code also contains Windows path profiles, but Linux is recommended for reproducing the paper experiments.

### (1) Prerequisites

Tyche is implemented using Python 3.10 and Java 21.

- **Python Environment:** Ensure Python 3.10 is installed. Install dependencies via:

```bash
 python3 -m pip install -r 02Tools/requirements.txt
```

- **Java Development:** JDK 21 and Maven 3.8+ to build Tyche and FeatureStatistics.

### (2) Download Target JVMs

Tyche performs differential testing across multiple JVM implementations, including both **legacy** and **latest** builds.

- **HotSpot:** install from [Java Downloads | Oracle](https://www.oracle.com/java/technologies/downloads/)

```bash
 wget https://download.oracle.com/java/21/archive/jdk-21.0.2_linux-x64_bin.tar.gz
```

- **OpenJ9:** install from [Semeru Runtime Downloads - IBM Developer](https://developer.ibm.com/languages/java/semeru-runtimes/downloads/)

```bash
 wget https://github.com/ibmruntimes/semeru21-binaries/releases/download/jdk-21.0.12.0/ibm-semeru-open-jdk_x64_linux_21.0.12.0.tar.gz
```

The commands above are examples for one HotSpot build and one OpenJ9 build. Download the remaining legacy and latest JDK 8, 11, 17, and 21 builds from the linked vendor pages. After organizing all required builds, the JVM directory should have the following structure:

```text
jdks/
├── hotspot-8
├── hotspot-8-old
...
├── hotspot-21
├── hotspot-21-old
├── openj9-8
├── openj9-8-old
...
├── openj9-21
└── openj9-21-old
```

### (3) Extract the Bug and Seed Corpora

Run the following commands from the repository root:

```bash
 unzip 03BugIssues/JIT_Type.zip -d 03BugIssues/
 unzip 03BugIssues/Javac_Type.zip -d 03BugIssues/
 unzip 04Seeds/TypeSeeds.zip -d 04Seeds/
 unzip 04Seeds/JFSeeds.zip -d 04Seeds/
```

### (4) Download and Build an Instrumented JVM

```bash
 git clone https://github.com/openjdk/jdk21u.git
 cd jdk21u
 bash configure \
    --with-boot-jdk=/path/to/jdks/hotspot-21 \
    --enable-native-coverage \
    --disable-warnings-as-errors
 make images JOBS=40
```



## 02 Empirical Study

Run the commands in this section from the repository root.

1. Crawl issues from JBS:

```bash
 cd 02Tools/Crawler/
 export GITHUB_TOKEN="your-token"
 python3 crawl_hotspot.py \
    --start-date 1999-07-06 \
    --end-date 2026-08-28 \
    --workers 10
```

The crawlers save issue metadata, complete Java reproducers found in issues or attachments, and available fixing patches. Set `GITHUB_TOKEN` to increase GitHub API and patch-download rate limits.

2. LLM-assisted analysis:

```bash
 cd 02Tools/LLMAnalysis/
 export DEEPSEEK_API_KEY="your-api-key"
 python3 aiQuery_jit.py \
    --dir ../Crawler/JIT_Issues/ \
    --stage screen \
    --platform deepseek \
    --model deepseek \
    --workers 10

 python3 aiQuery_jit.py \
    --dir ../Crawler/JIT_Issues/ \
    --stage analyze \
    --platform deepseek \
    --model deepseek \
    --workers 10
```

`--stage screen` filters out type-related bug cases, while `--stage analyze` performs analysis on the filtered bug cases.

3. Summarize the results:

```bash
 python3 02Tools/LLMAnalysis/analyze_jit_llm_results.py \
    --dir 03BugIssues/JIT_Type/ \
    --model deepseek
```

`03BugIssues/JIT_Type/` and `03BugIssues/Javac_Type/` contain type-related bug cases that have been analyzed and confirmed. You may directly run `analyze_jit_llm_results.py` for statistics.

4. Extract source-level feature statistics

```bash
 cd 02Tools/FeatureStatistics/
 mvn clean package

 java -jar FeatureStatistics/target/FeatureStatistics-1.0-SNAPSHOT.jar -t 03BugIssues/JIT_Type/
 java -jar FeatureStatistics/target/FeatureStatistics-1.0-SNAPSHOT.jar -t 03BugIssues/Javac_Type/
```

The generated statistics are written to `summary.json` and the other report files in each input corpus directory.

## 03 Running Experiments

### Main Experiment

Run the following commands from the repository root:

```bash
 cd 01Code/
 mvn clean package
 java -jar fuzz-app/target/fuzz-app-1.0-SNAPSHOT.jar \
    --fuzzConfig fuzz.yaml \
    --filterConfig seed-filter.yaml \
    --difftestConfig difftest.yaml
```

You can change the arguments by editing the YAML files.

- `fuzz.yaml`

  - `log.enabled`: Enables logging. We recommend disabling it during experiments to avoid unnecessary overhead.

  - `project.linux.seed_dir`:  seed pool directory;
  - `project.linux.output_dir`:  output directory;
  - `FuzzingEngine.max_mutation_depth`: Corresponds to $N$ in the paper.
  - `FuzzingEngine.max_mutations_per_seed`: Corresponds to $M$ in the paper.

- `difftest.yaml`
  - `difftest.java_version`: Selects the JVM version used for differential testing.

### Coverage Experiment

Set `linux.jvm` in `difftest.yaml` to the instrumented JVM. After the experiment finishes, copy the collector to the root of the instrumented OpenJDK source tree and run it there:

```bash
 cp 02Tools/CoverageCollector/collect.sh /path/to/instrumented-jdk/
 cd /path/to/instrumented-jdk/
 bash collect.sh
```
