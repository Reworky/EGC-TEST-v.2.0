#!/usr/bin/env bash
# Агент выкладки gamebot на СЕРВЕРЕ (root). Его запускает systemd, когда бот кладёт файл-заявку в data/deploy/request
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

mask() { sed -E 's/(token|TOKEN)=[^&, "]*/\1=***/g'; }

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

do_check() {
  git -C "$REPO" fetch origin main -q >/dev/null 2>&1 || true
  local base=""
  [ -f "$DIR/deployed_commit" ] && base=$(cut -d' ' -f1 "$DIR/deployed_commit")
  [ -n "$base" ] || base=HEAD
  git -C "$REPO" log --format='%h %s' "$base..origin/main" 2>/dev/null | head -20 > "$DIR/pending.tmp"
  mv "$DIR/pending.tmp" "$DIR/pending.txt"
  now > "$DIR/heartbeat"
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

run_request() {
  [ -f "$DIR/request" ] || exit 0
  local act="" by=""
  read -r act by < "$DIR/request" || true
  rm -f "$DIR/request"
  case "$by" in ''|*[!0-9]*) by=0 ;; esac
  case "$act" in check|deploy|rollback) ;; *) exit 0 ;; esac
  exec 9>"$LOCK"
  if ! flock -n 9; then exit 0; fi   # уже идёт другая выкладка - повторную заявку игнорируем
  case "$act" in
    check) do_check ;;
    deploy) do_deploy "$by" ;;
    rollback) do_rollback "$by" ;;
  esac
}

check_locked() {
  exec 9>"$LOCK"
  flock -n 9 || exit 0
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
