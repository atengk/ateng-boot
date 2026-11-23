#!/usr/bin/env bash
# ==============================================================================
# Git 远端操作超时守卫与安全执行共享库
#
# 提供统一的远端探测超时上限、推送防悬挂、受管临时文件确定性清理、
# 退出码常量契约以及跨平台 (Linux / macOS / Windows Git Bash / BusyBox) 命令执行器。
#
# @author Ateng
# @since 2026-11-12
# ==============================================================================

if [ "${_ATENG_GIT_REMOTE_GUARD_LOADED:-0}" = "1" ]; then
  return 0
fi
_ATENG_GIT_REMOTE_GUARD_LOADED=1

# 远端探测默认超时上限 (秒)、推送低速与总时长上限 (秒)
readonly REMOTE_PROBE_TIMEOUT_SECONDS=5
readonly REMOTE_PUSH_TIMEOUT_SECONDS=120
readonly REMOTE_LOW_SPEED_LIMIT_BPS=1000
readonly REMOTE_PUSH_LOW_SPEED_TIME_SECONDS=30

# Git 远端命令标准退出码常量
readonly GIT_EXIT_REMOTE_MISSING=2
readonly GIT_EXIT_TIMEOUT=124
readonly GIT_EXIT_TIMEOUT_TERM=143

GIT_REMOTE_STDOUT=""
GIT_REMOTE_STDERR=""
_GUARD_TEMP_FILES=()

# 确定性清理当前尚未回收的受管临时文件
_cleanup_guard_temp_files() {
  local f
  for f in "${_GUARD_TEMP_FILES[@]}"; do
    [ -n "$f" ] && rm -f "$f" 2>/dev/null || true
  done
  _GUARD_TEMP_FILES=()
}

trap '_cleanup_guard_temp_files' EXIT
trap '_cleanup_guard_temp_files; exit 130' INT
trap '_cleanup_guard_temp_files; exit 143' TERM

# 申请受管临时文件并立即入队登记，消除中途中断或 set -e 导致的泄漏
_alloc_guard_temp_file() {
  local tmp_file
  tmp_file=$(mktemp)
  _GUARD_TEMP_FILES+=("$tmp_file")
  printf "%s" "$tmp_file"
}

# 判断当前会话是否处于非交互模式
is_non_interactive_session() {
  local auto_confirm="${1:-false}"
  [ "$auto_confirm" = true ] || [ ! -t 0 ]
}

# 打印远端命令标准错误输出末尾摘要
print_remote_stderr_tail() {
  if [ -n "$GIT_REMOTE_STDERR" ]; then
    printf "%s\n" "$GIT_REMOTE_STDERR" | tail -n 5
  fi
}

# 探测宿主环境可用的硬超时命令
_detect_timeout_cmd() {
  if command -v timeout >/dev/null 2>&1; then
    printf "timeout"
  elif command -v gtimeout >/dev/null 2>&1; then
    printf "gtimeout"
  fi
}

# 统一规范化超时退出码 (兼容 GNU 124、BusyBox 143 及 Git HTTP 低速超时报错)
_normalize_timeout_exit() {
  local rc="$1"
  local stderr_text="${2:-}"
  if [ "$rc" -eq "$GIT_EXIT_TIMEOUT" ] || [ "$rc" -eq "$GIT_EXIT_TIMEOUT_TERM" ]; then
    return "$GIT_EXIT_TIMEOUT"
  fi
  if [ "$rc" -ne 0 ] && [ -n "$stderr_text" ] && printf "%s" "$stderr_text" | grep -Eiq "Operation timed out|less than [0-9]+ bytes/sec|timed out"; then
    return "$GIT_EXIT_TIMEOUT"
  fi
  return "$rc"
}

# 共享超时执行内核：优先使用 timeout/gtimeout，缺失时启用纯 Bash 看门狗兜底
# 参数: <timeout_sec> <disable_prompt:true|false> <out_target> <err_target> -- <cmd...>
_run_cmd_with_watchdog() {
  local timeout_sec="$1"
  local disable_prompt="$2"
  local out_target="$3"
  local err_target="$4"
  shift 5

  local timeout_cmd rc=0
  timeout_cmd=$(_detect_timeout_cmd)

  local env_prefix=()
  if [ "$disable_prompt" = true ]; then
    env_prefix=(env GIT_TERMINAL_PROMPT=0)
  fi

  if [ -n "$timeout_cmd" ]; then
    if [ -n "$out_target" ]; then
      if "${env_prefix[@]}" "$timeout_cmd" "$timeout_sec" "$@" >"$out_target" 2>"$err_target"; then
        rc=0
      else
        rc=$?
      fi
    else
      if "${env_prefix[@]}" "$timeout_cmd" "$timeout_sec" "$@"; then
        rc=0
      else
        rc=$?
      fi
    fi
    return "$rc"
  fi

  # 兼容无 timeout/gtimeout 的精简宿主 (如裸 macOS)：通过看门狗进程与非空标记文件保障硬超时
  local timed_out_flag
  timed_out_flag=$(_alloc_guard_temp_file)

  if [ -n "$out_target" ]; then
    "${env_prefix[@]}" "$@" >"$out_target" 2>"$err_target" &
  else
    "${env_prefix[@]}" "$@" &
  fi
  local cmd_pid=$!

  (
    sleep "$timeout_sec"
    if kill -0 "$cmd_pid" 2>/dev/null; then
      printf "1" > "$timed_out_flag"
      kill -TERM "$cmd_pid" 2>/dev/null || true
      sleep 1
      kill -KILL "$cmd_pid" 2>/dev/null || true
    fi
  ) >/dev/null 2>&1 &
  local wd_pid=$!

  if wait "$cmd_pid" 2>/dev/null; then
    rc=0
  else
    rc=$?
  fi
  kill -TERM "$wd_pid" 2>/dev/null || true
  wait "$wd_pid" 2>/dev/null || true

  if [ -s "$timed_out_flag" ]; then
    rc="$GIT_EXIT_TIMEOUT"
  fi
  rm -f "$timed_out_flag" 2>/dev/null || true
  return "$rc"
}

# 带超时与防交互阻塞守卫的 Git 远端探测/拉取执行器
# 将 stdout 与 stderr 分别落盘至受管临时文件：
# 1. 隔离 stderr 告警，保证机读 stdout（如 ls-remote SHA 解析）绝对纯净
# 2. 规避 Windows/MSYS2 下命令替换管道被孙子进程占用导致 timeout 返回后仍阻塞
run_git_with_timeout() {
  local timeout_sec="$1"
  shift

  # 低速阈值略高于硬超时 1s，优先由硬超时稳定返回 124，同时保留 HTTP 假死保护
  local low_speed_sec=$((timeout_sec + 1))
  local git_args=(-c "http.lowSpeedLimit=${REMOTE_LOW_SPEED_LIMIT_BPS}" -c "http.lowSpeedTime=${low_speed_sec}" "$@")

  local out_file err_file rc=0
  out_file=$(_alloc_guard_temp_file)
  err_file=$(_alloc_guard_temp_file)

  if _run_cmd_with_watchdog "$timeout_sec" true "$out_file" "$err_file" -- git "${git_args[@]}"; then
    rc=0
  else
    rc=$?
  fi

  GIT_REMOTE_STDOUT=$(cat "$out_file" 2>/dev/null || true)
  GIT_REMOTE_STDERR=$(cat "$err_file" 2>/dev/null || true)
  _cleanup_guard_temp_files

  _normalize_timeout_exit "$rc" "$GIT_REMOTE_STDERR"
}

# 带传输防悬挂与超时上限的 Git Push 执行器 (直接流式输出至当前终端)
run_git_push_guarded() {
  local auto_confirm="$1"
  shift

  local disable_prompt=false
  if is_non_interactive_session "$auto_confirm"; then
    disable_prompt=true
  fi

  local git_args=(-c "http.lowSpeedLimit=${REMOTE_LOW_SPEED_LIMIT_BPS}" -c "http.lowSpeedTime=${REMOTE_PUSH_LOW_SPEED_TIME_SECONDS}" push "$@")
  local rc=0
  if _run_cmd_with_watchdog "$REMOTE_PUSH_TIMEOUT_SECONDS" "$disable_prompt" "" "" -- git "${git_args[@]}"; then
    rc=0
  else
    rc=$?
  fi

  _cleanup_guard_temp_files
  _normalize_timeout_exit "$rc"
}
