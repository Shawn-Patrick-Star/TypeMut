from __future__ import annotations

import json
import os
import re
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional


PLATFORMS: Dict[str, Dict[str, Any]] = {
    "deepseek": {
        "base_url": "https://api.deepseek.com",
        "api_key_env": "DEEPSEEK_API_KEY",
        "models": {"deepseek": "deepseek-v4-flash"},
    },
    "kimi": {
        "base_url": "https://api.moonshot.cn/v1",
        "api_key_env": "KIMI_API_KEY",
        "models": {"kimi": "moonshot-v1-32k"},
    },
    "aliyun": {
        "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "api_key_env": "ALIYUN_API_KEY",
        "models": {"qwen": "qwen-max"},
    },
    "siliconflow": {
        "base_url": "https://api.siliconflow.cn/v1",
        "api_key_env": "SILICONFLOW_API_KEY",
        "models": {
            "qwen": "Qwen/Qwen3-32B",
            "deepseek": "deepseek-ai/DeepSeek-V3.2",
        },
    },
    "modelscope": {
        "base_url": "https://api-inference.modelscope.cn/v1/",
        "api_key_env": "MODELSCOPE_API_KEY",
        "models": {
            "deepseek": "deepseek-ai/DeepSeek-V3.2",
            "qwen": "Qwen/Qwen3-235B-A22B",
        },
    },
}


_LOG_LOCK = threading.Lock()


def log(message: str, log_file=None) -> None:
    with _LOG_LOCK:
        print(message, flush=True)
        if log_file is not None:
            with log_file.open("a", encoding="utf-8") as f:
                f.write(str(message) + "\n")


def safe_name(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", text).strip("_") or "model"


def result_filename(model_alias: str, stage: str) -> str:
    suffix = "screen" if stage == "screen" else "type"
    return f"llm_{safe_name(model_alias)}_{suffix}.json"


def read_json_object(path: Path) -> Optional[Dict[str, Any]]:
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
        return obj if isinstance(obj, dict) else None
    except (OSError, json.JSONDecodeError, UnicodeDecodeError):
        return None


def strip_reasoning_and_fences(raw: str) -> str:
    cleaned = re.sub(
        r"<think>.*?</think>", "", raw or "", flags=re.DOTALL | re.IGNORECASE
    ).strip()
    fenced = re.search(
        r"```(?:json)?\s*(.*?)```", cleaned, flags=re.DOTALL | re.IGNORECASE
    )
    return fenced.group(1).strip() if fenced else cleaned


def extract_results(raw: str) -> Optional[List[Dict[str, Any]]]:
    cleaned = strip_reasoning_and_fences(raw)
    if not cleaned:
        return None

    obj: Any = None
    try:
        obj = json.loads(cleaned)
    except json.JSONDecodeError:
        start, end = cleaned.find("{"), cleaned.rfind("}")
        if 0 <= start < end:
            try:
                obj = json.loads(cleaned[start : end + 1])
            except json.JSONDecodeError:
                pass

    if isinstance(obj, dict) and isinstance(obj.get("results"), list):
        return [x for x in obj["results"] if isinstance(x, dict)]
    if isinstance(obj, list):
        return [x for x in obj if isinstance(x, dict)]
    return None


def usage_to_dict(usage: Any) -> Dict[str, int]:
    if usage is None:
        return {}

    try:
        data = usage.model_dump() if hasattr(usage, "model_dump") else dict(usage)
    except Exception:
        data = {}

    result: Dict[str, int] = {}
    keys = (
        "prompt_tokens",
        "completion_tokens",
        "total_tokens",
        "prompt_cache_hit_tokens",
        "prompt_cache_miss_tokens",
        "cache_hit_tokens",
        "cache_miss_tokens",
    )
    for key in keys:
        value = data.get(key, getattr(usage, key, None))
        if isinstance(value, (int, float)):
            result[key] = int(value)

    details = data.get("prompt_tokens_details")
    if isinstance(details, dict):
        cached = details.get("cached_tokens")
        if isinstance(cached, (int, float)):
            result["cached_tokens"] = int(cached)
    return result


@dataclass
class LLMResponse:
    text: str
    usage: Dict[str, int]
    finish_reason: str = ""


class LLMClient:
    """Small OpenAI-compatible client shared by JIT and javac analyzers."""

    def __init__(
        self,
        *,
        platform: str,
        model_alias: str,
        model_id: Optional[str] = None,
        base_url: Optional[str] = None,
        api_key_env: Optional[str] = None,
        timeout: int = 120,
    ):
        cfg = PLATFORMS.get(platform, {})
        self.platform = platform
        self.base_url = base_url or cfg.get("base_url")
        self.api_key_env = api_key_env or cfg.get("api_key_env")
        self.model_id = model_id or cfg.get("models", {}).get(model_alias)

        if platform == "custom" and not self.model_id:
            self.model_id = model_alias
        if not self.base_url:
            raise ValueError("No base URL resolved; pass --base-url.")
        if not self.api_key_env:
            raise ValueError("No API-key environment variable resolved; pass --api-key-env.")
        if not self.model_id:
            raise ValueError("No model id resolved; pass --model-id.")

        api_key = os.getenv(self.api_key_env, "")
        if not api_key:
            raise ValueError(f"Environment variable {self.api_key_env} is not set.")

        try:
            import httpx
            from openai import OpenAI
        except ImportError as exc:
            raise RuntimeError(
                "API mode requires the 'openai' and 'httpx' packages."
            ) from exc

        # trust_env=True preserves HTTP(S)_PROXY on servers that require it.
        self._client = OpenAI(
            api_key=api_key,
            base_url=self.base_url,
            http_client=httpx.Client(timeout=timeout, trust_env=True),
        )

    def complete(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        max_tokens: int,
        retries: int = 3,
    ) -> Optional[LLMResponse]:
        last_error: Optional[Exception] = None
        for attempt in range(1, retries + 1):
            try:
                kwargs: Dict[str, Any] = {
                    "model": self.model_id,
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                    "stream": False,
                    "max_tokens": max_tokens,
                }

                # DeepSeek V4 Flash / Pro 默认开启 thinking。
                # TypeFuzz 是大规模结构化抽取任务，因此显式关闭 thinking。
                if self.platform == "deepseek":
                    kwargs["extra_body"] = {
                        "thinking": {
                            "type": "disabled"
                        },
                        "response_format": {
                            "type": "json_object"
                        }
                    }
                elif self.platform in {"modelscope", "siliconflow"}:
                    kwargs["extra_body"] = {
                        "enable_thinking": False
                    }

                response = self._client.chat.completions.create(**kwargs)
                choice = response.choices[0]
                message = choice.message

                return LLMResponse(
                    text=message.content or "",
                    usage=usage_to_dict(
                        getattr(response, "usage", None)
                    ),
                    finish_reason=getattr(
                        choice,
                        "finish_reason",
                        "",
                    ) or "",
                )
            except Exception as exc:
                last_error = exc
                if attempt < retries:
                    time.sleep(2 * attempt)

        if last_error:
            raise RuntimeError(str(last_error)) from last_error
        return None


class UsageTracker:
    def __init__(self):
        self._lock = threading.Lock()
        self.totals = {
            "api_calls": 0,
            "request_chars": 0,
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
            "prompt_cache_hit_tokens": 0,
            "prompt_cache_miss_tokens": 0,
            "cached_tokens": 0,
        }

    def add(self, usage: Dict[str, int], request_chars: int) -> None:
        with self._lock:
            self.totals["api_calls"] += 1
            self.totals["request_chars"] += request_chars
            for key, value in usage.items():
                if key in self.totals:
                    self.totals[key] += int(value)

    def snapshot(self) -> Dict[str, int]:
        with self._lock:
            return dict(self.totals)
