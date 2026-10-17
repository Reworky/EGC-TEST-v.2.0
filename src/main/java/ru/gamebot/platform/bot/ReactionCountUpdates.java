package ru.gamebot.platform.bot;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.Update;

/** Достаёт из апдейта Telegram общее число реакций под постом канала (message_reaction_count, Bot API 7.0). Через рефлексию: если
 *  версия библиотеки telegrambots не знает этот тип апдейта, метода getMessageReactionCount просто нет - тогда цель по реакциям не
 *  обновляется, а бот продолжает работать как раньше (в лог пишется одно предупреждение), сборка при этом не ломается. */
@Slf4j
final class ReactionCountUpdates {

    record Count(Long chatId, String chatUsername, Integer messageId, int total) {}

    private static volatile boolean warned;

    private ReactionCountUpdates() {}

    static Count extract(Update update) {
        try {
            Object rc = Update.class.getMethod("getMessageReactionCount").invoke(update);
            if (rc == null) return null;
            Object chat = rc.getClass().getMethod("getChat").invoke(rc);
            Long chatId = chat == null ? null : ((Number) chat.getClass().getMethod("getId").invoke(chat)).longValue();
            String username = chat == null ? null : (String) chat.getClass().getMethod("getUserName").invoke(chat);
            Integer messageId = ((Number) rc.getClass().getMethod("getMessageId").invoke(rc)).intValue();
            Object reactions = rc.getClass().getMethod("getReactions").invoke(rc);
            int total = 0;
            if (reactions instanceof List<?> list) {
                for (Object r : list) {
                    total += ((Number) r.getClass().getMethod("getTotalCount").invoke(r)).intValue();
                }
            }
            return new Count(chatId, username, messageId, total);
        } catch (NoSuchMethodException e) {
            if (!warned) {
                warned = true;
                log.warn("Reaction counts unavailable: telegrambots library has no Update.getMessageReactionCount (club goal by reactions will not update)");
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to read message_reaction_count update", e);
            return null;
        }
    }
}
