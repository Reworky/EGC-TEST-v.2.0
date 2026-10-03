#!/usr/bin/env bash
# Агент выкладки gamebot на СЕРВЕРЕ (root). Принимает ТОЛЬКО слова check, deploy, rollback и справочные status, logs, errors, restart, cleanup. Его запускает systemd, когда бот кладёт файл-заявку в data/deploy/request
# (кнопка «🚀 Обновить бота» в админке), и по таймеру раз в 2 минуты для проверки новых коммитов.
# Бот ничего кроме записи заявки сделать не может: агент принимает ТОЛЬКО слова check / deploy / rollback
# и запускает фиксированные действия ниже. Сам скрипт после установки живёт в /usr/local/bin (вне репозитория),
# чтобы git pull во время выкладки не подменил его на лету.
#
# Файлы в data/deploy (внутри контейнера это /data/deploy):
#   request          заявка от бота: "<check|deploy|rollback> <telegram_id>"
#   status           состояние: state=idle|running|ok|failed|rolled_back, action, requested_by, started_at, finished_at,
#                    commit, subject, prev_commit, message
#   deployed_commit  "<хеш> <тема>" коммита, который сейчас работает
#   pending.txt      коммиты, которые ещё не выложены ("<хеш> <тема>")
#   heartbeat        время последней проверки (unix), по нему бот понимает, что агент жив
#   last.log         лог последней выкладки (токены замаскированы)
#   disk_pct         занятое место на диске в процентах (обновляется каждую проверку, по нему бот предупреждает админов)
#   last_cleanup     время (unix) последней автоуборки Docker
#   out.txt, out_meta  результат справочных действий status / logs / errors / restart (раздел «🖥 Сервер» в админке):
#                    out.txt - текст ответа (токены замаскированы), out_meta - action, requested_by, finished_at (мс), ok
set -u

REPO="${GAMEBOT_REPO:-/root/gamebot}"
DIR="${GAMEBOT_DEPLOY_DIR:-$REPO/data/deploy}"
LOCK="${GAMEBOT_LOCK:-/var/lock/gamebot-deploy.lock}"
IMAGE=gamebot

now() { date +%s; }

write_status() { # state action by started finished commit subject prev message
  local tmp="$DIR/status.tmp"
  {
    echo "state=$1"; echo "action=$2"; echo "requested_by=$3"; echo "started_at=$4"; echo "finished_at=$5"
    echo "commit=$6"; echo "subject=${7//$'\n'/ }"; echo "prev_commit=$8"; echo "message=${9//$'\n'/ }"
  } > "$tmp" && mv "$tmp" "$DIR/status"
}

mask() { sed -E 's/(token|TOKEN)=[^&, "]*/\1=***/g; s/(^|[^0-9])[0-9]{8,12}:[A-Za-z0-9_-]{30,}/\1***/g'; }

start_container() {
  docker stop gamebot >/dev/null 2>&1 || true
  docker rm gamebot >/dev/null 2>&1 || true
  docker run -d --name gamebot --restart unless-stopped --network gamebot-net -p 8090:8090 \
    -v "$REPO/data:/data" --env-file "$REPO/.env" "$1" >/dev/null
}

# Ждём до ~3 минут, пока приложение напишет «Started ...» в лог; если контейнер упал раньше - сразу неудача
wait_started() {
  local i
  for i in $(seq 1 60); do
    sleep 3
    if docker logs gamebot 2>&1 | grep -q "Started GamePlatformBotApplication"; then return 0; fi
    [ "$(docker inspect -f '{{.State.Running}}' gamebot 2>/dev/null)" = "true" ] || return 1
  done
  return 1
}

head_line() { git -C "$REPO" log -1 --format='%h %s' 2>/dev/null; }

disk_pct() { df -P / 2>/dev/null | awk 'NR==2{gsub("%","",$5); print $5}'; }
disk_free_mb() { df -Pk / 2>/dev/null | awk 'NR==2{print int($4/1024)}'; }

# Уборка Docker: кэш сборки и «висячие» образы без имени. Образ для отката (gamebot:prev) и работающий образ имеют имена и
# не удаляются (image prune БЕЗ -a). until - возраст кэша сборки, который можно удалять ("all" - весь неиспользуемый кэш).
cleanup_docker() { # until
  if [ "${1:-48h}" = "all" ]; then docker builder prune -af >/dev/null 2>&1 || true
  else docker builder prune -f --filter "until=${1:-48h}" >/dev/null 2>&1 || true; fi
  docker image prune -f >/dev/null 2>&1 || true
  now > "$DIR/last_cleanup"
}

# Автоуборка: после успешной выкладки (кэш старше 48 ч) и при нехватке места (>=85%, не чаще раза в 6 часов, весь кэш).
auto_cleanup() { # reason: deploy|lowdisk
  case "$1" in
    deploy) cleanup_docker 48h ;;
    lowdisk)
      local last pct
      pct=$(disk_pct); last=$(cat "$DIR/last_cleanup" 2>/dev/null || echo 0)
      case "$pct" in ''|*[!0-9]*) return ;; esac
      if [ "$pct" -ge 85 ] && [ $(( $(now) - last )) -gt 21600 ]; then cleanup_docker all; fi ;;
  esac
}

do_cleanup() { # by - ручная уборка кнопкой «🧹 Почистить диск»
  local by=$1 before after
  before=$(disk_free_mb)
  cleanup_docker all
  after=$(disk_free_mb)
  echo "Освобождено: $(( after - before )) МБ. Свободно: ${after} МБ, диск занят на $(disk_pct)%." > "$DIR/out.txt"
  write_out cleanup "$by" 1 "Уборка диска"
}

do_check() {
  git -C "$REPO" fetch origin main -q >/dev/null 2>&1 || true
  local base=""
  [ -f "$DIR/deployed_commit" ] && base=$(cut -d' ' -f1 "$DIR/deployed_commit")
  [ -n "$base" ] || base=HEAD
  git -C "$REPO" log --format='%h %s' "$base..origin/main" 2>/dev/null | head -20 > "$DIR/pending.tmp"
  mv "$DIR/pending.tmp" "$DIR/pending.txt"
  now > "$DIR/heartbeat"
  disk_pct > "$DIR/disk_pct" 2>/dev/null || true
}

do_deploy() { # by
  local by=$1 started old_head new_line new_hash new_subj
  started=$(now)
  write_status running deploy "$by" "$started" 0 "" "" "" "Сборка запущена"
  : > "$DIR/last.log"
  old_head=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null)
  # Сначала git pull и сборка образа под временным именем :new - старый контейнер при этом продолжает работать
  # pipefail в подоболочке: код возврата - у git/docker, а не у sed-маскировки токенов. (Сравнивать время создания образа
  # нельзя: сборка целиком из кэша даёт образ со старой датой, хотя она успешна, например при пересоздании после смены .env.)
  if ! ( set -o pipefail
         git -C "$REPO" pull --ff-only 2>&1 | mask >> "$DIR/last.log" &&
         docker build -t "$IMAGE:new" "$REPO" 2>&1 | mask >> "$DIR/last.log" ); then
    write_status failed deploy "$by" "$started" "$(now)" "" "" "$old_head" "Сборка не прошла, продолжает работать прежняя версия"
    return
  fi
  new_line=$(head_line); new_hash=${new_line%% *}; new_subj=${new_line#* }
  docker tag "$IMAGE" "$IMAGE:prev" >/dev/null 2>&1 || true
  if [ -f "$DIR/deployed_commit" ]; then cp "$DIR/deployed_commit" "$DIR/prev_deployed_commit"; fi
  docker tag "$IMAGE:new" "$IMAGE"
  start_container "$IMAGE" 2>&1 | mask >> "$DIR/last.log"
  if wait_started; then
    echo "$new_line" > "$DIR/deployed_commit"
    write_status ok deploy "$by" "$started" "$(now)" "$new_hash" "$new_subj" "$old_head" "Готово"
    auto_cleanup deploy
    do_check
    return
  fi
  { echo "--- новая версия не запустилась, хвост лога контейнера ---"; docker logs gamebot --tail 60 2>&1; } | mask >> "$DIR/last.log"
  if docker image inspect "$IMAGE:prev" >/dev/null 2>&1; then
    docker tag "$IMAGE:prev" "$IMAGE"
    start_container "$IMAGE" 2>&1 | mask >> "$DIR/last.log"
    wait_started || true
  fi
  write_status rolled_back deploy "$by" "$started" "$(now)" "$new_hash" "$new_subj" "$old_head" "Новая версия не запустилась, возвращена предыдущая"
  do_check
}

do_rollback() { # by
  local by=$1 started line
  started=$(now)
  write_status running rollback "$by" "$started" 0 "" "" "" "Откат запущен"
  : > "$DIR/last.log"
  if ! docker image inspect "$IMAGE:prev" >/dev/null 2>&1; then
    write_status failed rollback "$by" "$started" "$(now)" "" "" "" "Нет сохранённого предыдущего образа"
    return
  fi
  # меняем местами текущий и предыдущий образы, чтобы повторный откат вернул обратно
  docker tag "$IMAGE" "$IMAGE:swap" >/dev/null 2>&1
  docker tag "$IMAGE:prev" "$IMAGE"
  docker tag "$IMAGE:swap" "$IMAGE:prev" >/dev/null 2>&1
  start_container "$IMAGE" 2>&1 | mask >> "$DIR/last.log"
  if wait_started; then
    if [ -f "$DIR/prev_deployed_commit" ]; then
      cp "$DIR/deployed_commit" "$DIR/swap_commit" 2>/dev/null || true
      cp "$DIR/prev_deployed_commit" "$DIR/deployed_commit"
      if [ -f "$DIR/swap_commit" ]; then mv "$DIR/swap_commit" "$DIR/prev_deployed_commit"; fi
    fi
    line=$(cat "$DIR/deployed_commit" 2>/dev/null)
    write_status ok rollback "$by" "$started" "$(now)" "${line%% *}" "${line#* }" "" "Откат выполнен"
  else
    { echo "--- после отката контейнер не запустился ---"; docker logs gamebot --tail 60 2>&1; } | mask >> "$DIR/last.log"
    write_status failed rollback "$by" "$started" "$(now)" "" "" "" "После отката контейнер не запустился"
  fi
  do_check
}

# ── Справочные действия раздела «🖥 Сервер» (2026-10-03): только чтение, кроме restart. Ответ - в out.txt + out_meta. ──
write_out() { # action by ok message
  local tmp="$DIR/out_meta.tmp" ms
  ms=$(date +%s%3N); case "$ms" in ''|*[!0-9]*) ms=$(( $(date +%s) * 1000 )) ;; esac
  { echo "action=$1"; echo "requested_by=$2"; echo "finished_at=$ms"; echo "ok=$3"; echo "message=${4//$'\n'/ }"; } > "$tmp" \
    && mv "$tmp" "$DIR/out_meta"
}

do_status() { # by
  local by=$1
  {
    echo "Контейнер: $(docker ps -a --filter 'name=^/gamebot$' --format '{{.Status}} · образ {{.Image}}' 2>&1)"
    echo "Запущен с: $(docker inspect -f '{{.State.StartedAt}}' gamebot 2>/dev/null)"
    echo "Сейчас работает: $(cat "$DIR/deployed_commit" 2>/dev/null)"
    echo "Ждут выкладки: $(wc -l < "$DIR/pending.txt" 2>/dev/null || echo 0) коммит(ов)"
    echo "Сервер: $(uptime | sed 's/^ *//')"
    echo "--- диск ---"; df -h / | tail -n +1
    echo "--- память ---"; free -m
    echo "--- nginx ---"; systemctl is-active nginx 2>&1
  } 2>&1 | mask | cut -c1-300 > "$DIR/out.txt"
  write_out status "$by" 1 "Статус сервера"
}

do_logs() { # by lines
  local by=$1 n=$2
  case "$n" in ''|*[!0-9]*) n=40 ;; esac
  [ "$n" -lt 10 ] && n=10
  [ "$n" -gt 150 ] && n=150
  docker logs gamebot --tail "$n" 2>&1 | cut -c1-300 | mask > "$DIR/out.txt"
  write_out logs "$by" 1 "Последние $n строк лога"
}

do_errors() { # by
  local by=$1
  docker logs gamebot --since 1h 2>&1 | grep -E '\bERROR\b|Exception' | tail -n 40 | cut -c1-300 | mask > "$DIR/out.txt"
  [ -s "$DIR/out.txt" ] || echo "За последний час ошибок в логе нет." > "$DIR/out.txt"
  write_out errors "$by" 1 "Ошибки за последний час"
}

do_restart() { # by
  local by=$1 since i
  since=$(now)
  write_status running restart "$by" "$since" 0 "" "" "" "Перезапуск запущен"
  if ! docker restart gamebot >/dev/null 2>&1; then
    echo "Не удалось выполнить docker restart" > "$DIR/out.txt"
    write_out restart "$by" 0 "Перезапуск не удался"
    write_status failed restart "$by" "$since" "$(now)" "" "" "" "Перезапуск не удался"
    return
  fi
  for i in $(seq 1 60); do
    sleep 3
    if docker logs gamebot --since "$since" 2>&1 | grep -q "Started GamePlatformBotApplication"; then
      echo "Контейнер перезапущен, бот поднялся." > "$DIR/out.txt"
      write_out restart "$by" 1 "Перезапуск выполнен"
      write_status ok restart "$by" "$since" "$(now)" "" "" "" "Перезапуск выполнен"
      return
    fi
    [ "$(docker inspect -f '{{.State.Running}}' gamebot 2>/dev/null)" = "true" ] || break
  done
  { echo "Бот не поднялся после перезапуска. Хвост лога:"; docker logs gamebot --tail 30 2>&1 | cut -c1-300; } | mask > "$DIR/out.txt"
  write_out restart "$by" 0 "После перезапуска бот не поднялся"
  write_status failed restart "$by" "$since" "$(now)" "" "" "" "После перезапуска бот не поднялся"
}

run_request() {
  [ -f "$DIR/request" ] || exit 0
  local act="" by="" arg=""
  read -r act by arg < "$DIR/request" || true
  rm -f "$DIR/request"
  case "$by" in ''|*[!0-9]*) by=0 ;; esac
  case "$act" in check|deploy|rollback|status|logs|errors|restart|cleanup) ;; *) exit 0 ;; esac
  exec 9>"$LOCK"
  if ! flock -n 9; then exit 0; fi   # уже идёт другая выкладка - повторную заявку игнорируем
  case "$act" in
    check) do_check ;;
    deploy) do_deploy "$by" ;;
    rollback) do_rollback "$by" ;;
    status) do_status "$by" ;;
    logs) do_logs "$by" "$arg" ;;
    errors) do_errors "$by" ;;
    restart) do_restart "$by" ;;
    cleanup) do_cleanup "$by" ;;
  esac
}

check_locked() {
  exec 9>"$LOCK"
  flock -n 9 || exit 0
  auto_cleanup lowdisk
  do_check
}

main() {
  mkdir -p "$DIR"
  case "${1:-}" in
    run) run_request ;;
    check) check_locked ;;
    *) echo "usage: $0 run|check" >&2; exit 2 ;;
  esac
}
main "$@"
exit $?
